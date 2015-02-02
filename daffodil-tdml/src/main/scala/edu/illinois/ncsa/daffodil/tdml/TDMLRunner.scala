/* Copyright (c) 2012-2015 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 * 
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 * 
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */

package edu.illinois.ncsa.daffodil.tdml

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.URI
import java.net.URL
import scala.io.Codec.string2codec
import scala.xml.Node
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.SAXParseException
import scala.xml.Utility
import org.xml.sax.InputSource
import com.ibm.icu.charset.CharsetICU
import edu.illinois.ncsa.daffodil.Tak
import edu.illinois.ncsa.daffodil.api.DFDL
import edu.illinois.ncsa.daffodil.api.DataLocation
import edu.illinois.ncsa.daffodil.api.ValidationMode
import edu.illinois.ncsa.daffodil.api.WithDiagnostics
import edu.illinois.ncsa.daffodil.api.DaffodilSchemaSource
import edu.illinois.ncsa.daffodil.api.UnitTestSchemaSource
import edu.illinois.ncsa.daffodil.api.URISchemaSource
import edu.illinois.ncsa.daffodil.api.EmbeddedSchemaSource
import edu.illinois.ncsa.daffodil.compiler.Compiler
import edu.illinois.ncsa.daffodil.configuration.ConfigurationLoader
import edu.illinois.ncsa.daffodil.dsom.EntityReplacer
import edu.illinois.ncsa.daffodil.dsom.ValidationError
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.externalvars.Binding
import edu.illinois.ncsa.daffodil.externalvars.ExternalVariablesLoader
import edu.illinois.ncsa.daffodil.processors.DFDLCharCounter
import edu.illinois.ncsa.daffodil.processors.GeneralParseFailure
import edu.illinois.ncsa.daffodil.processors.IterableReadableByteChannel
import edu.illinois.ncsa.daffodil.util.Error
import edu.illinois.ncsa.daffodil.util.LogLevel
import edu.illinois.ncsa.daffodil.util.Logging
import edu.illinois.ncsa.daffodil.util.Misc
import edu.illinois.ncsa.daffodil.util.Misc.bits2Bytes
import edu.illinois.ncsa.daffodil.util.Misc.bytes2Bits
import edu.illinois.ncsa.daffodil.util.Misc.hex2Bits
import edu.illinois.ncsa.daffodil.util.SchemaUtils
import edu.illinois.ncsa.daffodil.util.Timer
import edu.illinois.ncsa.daffodil.xml.DaffodilXMLLoader
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.util.Bits
import edu.illinois.ncsa.daffodil.processors.charset.CharsetUtils
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channels
import java.nio.charset.CoderResult
import java.io.InputStream
import edu.illinois.ncsa.daffodil.processors.charset.NonByteSizeCharsetEncoderDecoder
import scala.language.postfixOps
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files

/**
 * Parses and runs tests expressed in IBM's contributed tdml "Test Data Markup Language"
 */

//
// TODO: validate the infoset XML (expected result) against the DFDL Schema, that is using it as an XML Schema
// for the infoset. This would prevent errors where the infoset instance and the schema drift apart under maintenance.
//
// TODO: validate the actual result against the DFDL Schema using it as an XML Schema. 
//
/**
 * TDML test suite runner
 *
 * Keep this independent of Daffodil, so that it can be used to run tests against other DFDL implementations as well.
 * E.g., it should only need an API specified as a collection of Scala traits, and some simple way to inject
 * dependency on one factory to create processors.
 *
 *
 * Use the validateTDMLFile arg to bypass validation of the TDML document itself.
 *
 * This is used for testing whether one can detect validation errors
 * in the DFDL schema.
 *
 * Without this, you can't get to the validation errors, because it
 * rejects the TDML file itself.
 */

class DFDLTestSuite(aNodeFileOrURL: Any,
  validateTDMLFile: Boolean = true,
  val validateDFDLSchemas: Boolean = true)
  extends Logging {

  val TMP_DIR = System.getProperty("java.io.tmpdir", ".")

  aNodeFileOrURL match {
    case _: URI => // ok
    case _: File => // ok
    case _: scala.xml.Node => // ok
    case x => Assert.usageError("argument was not a scala.xmlNode, File, or URI: " + x)
  }

  val errorHandler = new org.xml.sax.ErrorHandler {
    def warning(exception: SAXParseException) = {
      loadingExceptions == exception +: loadingExceptions
      System.err.println("TDMLRunner Warning: " + exception.getMessage())
    }

    def error(exception: SAXParseException) = {
      loadingExceptions = exception :: loadingExceptions
      System.err.println("TDMLRunner Error: " + exception.getMessage())
      isLoadingError = true
    }
    def fatalError(exception: SAXParseException) = {
      loadingExceptions == exception +: loadingExceptions
      System.err.println("TDMLRunner Fatal Error: " + exception.getMessage())
      isLoadingError = true
    }
  }

  var isLoadingError: Boolean = false

  var loadingExceptions: List[Exception] = Nil

  def getLoadingDiagnosticMessages() = {
    val msgs = loadingExceptions.map { _.toString() }.mkString(" ")
    msgs
  }

  /**
   * our loader here accumulates load-time errors here on the
   * test suite object.
   */
  private val augmentExistingLocationInfo = true
  val loader = new DaffodilXMLLoader(errorHandler)
  loader.setValidation(validateTDMLFile)

  val (ts, tsURI) = aNodeFileOrURL match {
    case tsNode: Node => {
      //
      // We were passed a literal schema node. This is for unit testing
      // purposes. 
      //
      val tmpDir = new File(TMP_DIR, "daffodil")
      tmpDir.mkdirs()

      val src = new UnitTestSchemaSource(tsNode, "", Some(tmpDir))
      val origNode = loader.load(src)
      //
      (origNode, src.uriForLoading)
    }
    case tdmlFile: File => {
      log(LogLevel.Info, "loading TDML file: %s", tdmlFile)
      val uri = tdmlFile.toURI()
      val newNode = loader.load(new URISchemaSource(uri))
      val res = (newNode, uri)
      log(LogLevel.Debug, "done loading TDML file: %s", tdmlFile)
      res
    }
    case uri: URI => {
      val newNode = loader.load(new URISchemaSource(uri))
      val res = (newNode, uri)
      res
    }
    case _ => Assert.usageError("not a Node, File, or URL")
  } // end match

  lazy val isTDMLFileValid = !this.isLoadingError

  var checkAllTopLevel: Boolean = false
  def setCheckAllTopLevel(flag: Boolean) {
    checkAllTopLevel = flag
  }

  val parserTestCases = (ts \ "parserTestCase").map { node => ParserTestCase(node, this) }
  //
  // Note: IBM started this TDML file format. They call an unparser test a "serializer" test.
  // We will use their TDML file names, but in the code here, we call it an UnparserTestCase
  //
  val unparserTestCases = (ts \ "serializerTestCase").map { node => UnparserTestCase(node, this) }
  val testCases: Seq[TestCase] = parserTestCases ++
    unparserTestCases
  val suiteName = (ts \ "@suiteName").text
  val suiteID = (ts \ "@ID").text
  val description = (ts \ "@description").text
  val embeddedSchemasRaw = (ts \ "defineSchema").map { node => DefinedSchema(node, this) }
  val embeddedConfigs = (ts \ "defineConfig").map { node => DefinedConfig(node, this) }

  val embeddedSchemas = {
    val embeddedSchemaGroups = embeddedSchemasRaw.groupBy { _.name }
    embeddedSchemaGroups.foreach {
      case (name, Seq(sch)) => // ok
      case (name, seq) =>
        // TDML XML schema has uniqueness check for this. Hence, this is just an Assert here
        // since it means that the validation of the TDML file didn't catch the duplicate name.
        Assert.usageError("More than one definition for embedded schema " + name)
    }
    embeddedSchemasRaw
  }

  def runAllTests(schema: Option[Node] = None) {
    if (isTDMLFileValid)
      testCases.map { _.run(schema) }
    else {
      log(Error("TDML file %s is not valid.", tsURI))
    }
  }

  def runPerfTest(testName: String, schema: Option[Node] = None) {
    var bytesProcessed: Long = 0
    var charsProcessed: Long = 0
    Tak.calibrate
    val ns = Timer.getTimeNS(testName, {
      val (by, ch) = runOneTestWithDataVolumes(testName, schema)
      bytesProcessed = by
      charsProcessed = ch
    })
    val takeonsThisRun = ns / Tak.takeons
    val bpns = ((bytesProcessed * 1.0) / ns)
    val kbps = bpns * 1000000
    val callsPerByte = 1 / (Tak.takeons * bpns)
    println("\nKB/sec = " + kbps)
    println("tak call equivalents per byte (takeons/byte) =  " + callsPerByte)
  }

  def runOneTest(testName: String, schema: Option[Node] = None, leakCheck: Boolean = false) {
    if (leakCheck) {
      System.gc()
      Thread.sleep(1) // needed to give tools like jvisualvm ability to "grab on" quickly
    }
    runOneTestWithDataVolumes(testName, schema)
  }

  def runOneTestWithDataVolumes(testName: String, schema: Option[Node] = None): (Long, Long) = {
    if (isTDMLFileValid) {
      val testCase = testCases.find(_.name == testName)
      testCase match {
        case None => throw new TDMLException("test " + testName + " was not found.")
        case Some(tc) => {
          return tc.run(schema)
        }
      }
    } else {
      log(Error("TDML file %s is not valid.", tsURI))
      val msgs = this.loadingExceptions.map { _.toString }.mkString(" ")
      throw new TDMLException(msgs)
    }
  }

  /**
   * Try a few possibilities to find the model/schema/tdml resources
   *
   * IBM's suites have funny model paths in them. We don't have that file structure,
   * so we look for the schema/model/tdml resources in the working directory, and in the same
   * directory as the tdml file, and some other variations.
   */
  def findTDMLResource(resName: String): Option[URI] = {
    val resPath = Paths.get(resName)
    val resolvedURI =
      if (Files.exists(resPath)) Some(resPath.toFile().toURI())
      else Misc.getResourceRelativeOption(resName, Some(tsURI))
    val res = resolvedURI.orElse {
      // try ignoring the directory part
      val parts = resName.split("/")
      if (parts.length > 1) { // if there is one
        val filePart = parts.last
        val secondTry = findTDMLResource(filePart) // recursively
        secondTry
      } else {
        None
      }
    }
    res
  }

  def findEmbeddedSchema(modelName: String): Option[DefinedSchema] = {
    // schemas defined with defineSchema take priority as names.
    val es = embeddedSchemas.find { defSch => defSch.name == modelName }
    es
  }

  def findSchemaFileName(modelName: String) = findTDMLResource(modelName)

  def findEmbeddedConfig(configName: String): Option[DefinedConfig] = {
    val ecfg = embeddedConfigs.find { defCfg => defCfg.name == configName }
    ecfg match {
      case Some(defConfig) => Some(defConfig)
      case None => None
    }
  }

  def findConfigFileName(configName: String) = findTDMLResource(configName)

}

abstract class TestCase(ptc: NodeSeq, val parent: DFDLTestSuite)
  extends Logging {

  def toOpt[T](n: Seq[T]) = {
    n match {
      case Seq() => None
      case Seq(a) => Some(a)
      // ok for it to error if there is more than one in sequence.
    }
  }

  val document = toOpt(ptc \ "document").map { node => new Document(node, this) }
  val infoset = toOpt(ptc \ "infoset").map { node => new Infoset(node, this) }
  val errors = toOpt(ptc \ "errors").map { node => new ExpectedErrors(node, this) }
  val warnings = toOpt(ptc \ "warnings").map { node => new ExpectedWarnings(node, this) }
  val validationErrors = toOpt(ptc \ "validationErrors").map { node => new ExpectedValidationErrors(node, this) }

  val name = (ptc \ "@name").text
  val ptcID = (ptc \ "@ID").text
  val id = name + (if (ptcID != "") "(" + ptcID + ")" else "")
  val root = (ptc \ "@root").text
  val model = (ptc \ "@model").text
  val config = (ptc \ "@config").text
  val description = (ptc \ "@description").text
  val unsupported = (ptc \ "@unsupported").text match {
    case "true" => true
    case "false" => false
    case _ => false
  }
  val validationMode = (ptc \ "@validation").text match {
    case "on" => ValidationMode.Full
    case "limited" => ValidationMode.Limited
    case _ => ValidationMode.Off
  }
  val shouldValidate = validationMode != ValidationMode.Off
  val expectsValidationError = if (validationErrors.isDefined) validationErrors.get.hasDiagnostics else false

  protected def runProcessor(processor: DFDL.ProcessorFactory,
    data: Option[DFDL.Input],
    nBits: Option[Long],
    infoset: Option[Infoset],
    errors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type): Unit

  protected def runProcessor(processor: DFDL.DataProcessor,
    data: Option[DFDL.Input],
    lengthLimitInBits: Option[Long],
    optInfoset: Option[Infoset],
    optErrors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type): Unit

  private def retrieveBindings(cfg: DefinedConfig): Seq[Binding] = {
    val bindings: Seq[Binding] = cfg.externalVariableBindings match {
      case None => Seq.empty
      case Some(bindingsNode) => ExternalVariablesLoader.getVariables(bindingsNode)
    }
    bindings
  }

  def getSuppliedSchema(schemaArg: Option[Node]): DaffodilSchemaSource = {
    val embeddedSchema = parent.findEmbeddedSchema(model)
    val schemaURI = parent.findSchemaFileName(model)
    val suppliedSchema = (schemaArg, embeddedSchema, schemaURI) match {
      case (None, None, None) => throw new TDMLException("Model '" + model + "' was not passed, found embedded in the TDML file, nor as a schema file.")
      case (None, Some(_), Some(_)) => throw new TDMLException("Model '" + model + "' is ambiguous. There is an embedded model with that name, AND a file with that name.")
      case (Some(node), _, _) => {
        // unit test case. There is no URI/file location
        if (model != "") throw new TDMLException("You supplied a model attribute, and a schema argument. Can't have both.")
        // note that in this case, since a node was passed in, this node has no file/line/col information on it
        // so error messages will end up being about some temp file. 
        UnitTestSchemaSource(node, name)
      }
      case (None, Some(defSchema), None) => {
        Assert.invariant(model != "") // validation of the TDML should prevent this
        EmbeddedSchemaSource(defSchema.xsdSchema, defSchema.name)
      }
      case (None, None, Some(uri)) => {
        //
        // In this case, we have a real TDML file (or resource) to open
        URISchemaSource(uri)
      }
    } // end match
    suppliedSchema
  }

  def run(schemaArg: Option[Node] = None): (Long, Long) = {
    val suppliedSchema = getSuppliedSchema(schemaArg)

    val cfg: Option[DefinedConfig] = config match {
      case "" => None
      case configName => {
        val cfgNode = parent.findEmbeddedConfig(configName)
        val cfgFileName = parent.findConfigFileName(configName)
        val optDefinedConfig = (cfgNode, cfgFileName) match {
          case (None, None) => None
          case (Some(_), Some(_)) => throw new TDMLException("Config '" + config + "' is ambiguous. There is an embedded config with that name, AND a file with that name.")
          case (Some(definedConfig), None) => Some(definedConfig)
          case (None, Some(uri)) => {
            // Read file, convert to definedConfig
            val node = ConfigurationLoader.getConfiguration(uri)
            val definedConfig = DefinedConfig(node, parent)
            Some(definedConfig)
          }
        }
        optDefinedConfig
      }
    }
    val externalVarBindings: Seq[Binding] = cfg match {
      case None => Seq.empty
      case Some(definedConfig) => retrieveBindings(definedConfig)
    }

    val compiler = Compiler(parent.validateDFDLSchemas)
    compiler.setDistinguishedRootNode(root, null)
    compiler.setCheckAllTopLevel(parent.checkAllTopLevel)
    compiler.setExternalDFDLVariables(externalVarBindings)

    val pf = compiler.compileSource(suppliedSchema)

    val data = document.map { _.data }
    val nBits = document.map { _.nBits }

    runProcessor(pf, data, nBits, infoset, errors, warnings, validationErrors, validationMode)

    val bytesProcessed = IterableReadableByteChannel.getAndResetCalls
    val charsProcessed = DFDLCharCounter.getAndResetCount
    log(LogLevel.Debug, "Bytes processed: " + bytesProcessed)
    log(LogLevel.Debug, "Characters processed: " + charsProcessed)
    (bytesProcessed, charsProcessed)
    // if we get here, the test passed. If we don't get here then some exception was
    // thrown either during the run of the test or during the comparison.
    // log(LogLevel.Debug, "Test %s passed.", id))
  }

  def verifyAllDiagnosticsFound(actual: WithDiagnostics, expectedDiags: Option[ErrorWarningBase]) = {
    val actualDiags = actual.getDiagnostics
    Assert.invariant(actualDiags.length > 0 && expectedDiags.isDefined)
    val actualDiagMsgs = actualDiags.map { _.toString }
    val expectedDiagMsgs = expectedDiags.map { _.messages }.getOrElse(Nil)
    // must find each expected warning message within some actual warning message.
    expectedDiagMsgs.foreach {
      expected =>
        {
          val wasFound = actualDiagMsgs.exists {
            actual => actual.toLowerCase.contains(expected.toLowerCase)
          }
          if (!wasFound) {
            throw new TDMLException("""Did not find diagnostic message """" +
              expected + """" in any of the actual diagnostic messages: """ + "\n" +
              actualDiagMsgs.mkString("\n"))
          }
        }
    }
  }

  def verifyAllDiagnosticsFound(actualDiags: Seq[edu.illinois.ncsa.daffodil.api.Diagnostic], expectedDiags: Option[ErrorWarningBase]) = {

    val actualDiagMsgs = actualDiags.map { _.toString }
    val expectedDiagMsgs = expectedDiags.map { _.messages }.getOrElse(Nil)

    if (expectedDiags.isDefined && actualDiags.length == 0) {
      throw new TDMLException(""""Diagnostic message(s) were expected but not found."""" +
        "\n" + """Expected: """ + expectedDiagMsgs.mkString("\n"))
    }

    // must find each expected warning message within some actual warning message.
    expectedDiagMsgs.foreach {
      expected =>
        {
          val wasFound = actualDiagMsgs.exists {
            actual => actual.toLowerCase.contains(expected.toLowerCase)
          }
          if (!wasFound) {
            throw new TDMLException("""Did not find diagnostic message """" +
              expected + """" in any of the actual diagnostic messages: """ + "\n" +
              actualDiagMsgs.mkString("\n"))
          }
        }
    }
  }

  def verifyNoValidationErrorsFound(actual: WithDiagnostics) = {
    val actualDiags = actual.getDiagnostics.filter(d => d.isInstanceOf[ValidationError])
    if (actualDiags.length != 0) {
      val actualDiagMsgs = actualDiags.map { _.toString }
      throw new TDMLException("Validation errors found where none were expected by the test case.\n" +
        actualDiagMsgs.mkString("\n"))
    }
  }

}

case class ParserTestCase(ptc: NodeSeq, parentArg: DFDLTestSuite)
  extends TestCase(ptc, parentArg) {

  def runProcessor(processor: DFDL.DataProcessor,
    data: Option[DFDL.Input],
    lengthLimitInBits: Option[Long],
    optInfoset: Option[Infoset],
    optErrors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) = {

    val nBits = lengthLimitInBits.get
    val dataToParse = data.get
    (optInfoset, optErrors) match {
      case (Some(infoset), None) => runParseExpectSuccess(processor, dataToParse, nBits, infoset, warnings, validationErrors, validationMode)
      case (None, Some(errors)) => runParseExpectErrors(processor, dataToParse, nBits, errors, warnings, validationErrors, validationMode)
      case _ => Assert.invariantFailed("Should be Some None, or None Some only.")
    }
  }

  def generateProcessor(pf: DFDL.ProcessorFactory, useSerializedParser: Boolean): DFDL.DataProcessor = {
    val p = pf.onPath("/")
    if (useSerializedParser) {
      val os = new java.io.ByteArrayOutputStream()
      val output = Channels.newChannel(os)
      p.save(output)

      val is = new java.io.ByteArrayInputStream(os.toByteArray)
      val input = Channels.newChannel(is)
      val compiler_ = Compiler()
      compiler_.reload(input)
    } else p
  }

  def runProcessor(pf: DFDL.ProcessorFactory,
    data: Option[DFDL.Input],
    lengthLimitInBits: Option[Long],
    optInfoset: Option[Infoset],
    optErrors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) = {

    val useSerializedParser = true
    val nBits = lengthLimitInBits.get
    val dataToParse = data.get

    (optInfoset, optErrors) match {
      case (Some(infoset), None) => {
        val diags = pf.getDiagnostics.map(_.getMessage).mkString("\n")
        if (pf.isError) {
          throw new TDMLException(diags)
        }

        val processor = this.generateProcessor(pf, useSerializedParser)
        runParseExpectSuccess(processor, dataToParse, nBits, infoset, warnings, validationErrors, validationMode)
      }

      case (None, Some(errors)) => {
        if (pf.isError) verifyAllDiagnosticsFound(pf, Some(errors))
        else {
          val processor = this.generateProcessor(pf, useSerializedParser)
          runParseExpectErrors(processor, dataToParse, nBits, errors, warnings, validationErrors, validationMode)
        }
      }

      case _ => Assert.invariantFailed("Should be Some None, or None Some only.")
    }
  }

  def verifyParseInfoset(actual: DFDL.ParseResult, infoset: Infoset) {
    val trimmed = Utility.trim(actual.result)
    //
    // Attributes on the XML like xsi:type and also namespaces (I think) are 
    // making things fail these comparisons, so we strip all attributes off (since DFDL doesn't 
    // use attributes at all)
    // 
    val actualNoAttrs = XMLUtils.removeAttributes(trimmed)
    // 
    // Would be great to validate the actuals against the DFDL schema, used as
    // an XML schema on the returned infoset XML.
    // Getting this to work is a bigger issue. What with stripping of attributes
    // and that our internal Daffodil XML Catalog has a special treatment of the
    // mapping of the XML Schema URI.
    // etc.
    // 
    // TODO: Fix so we can validate here.
    //

    // Something about the way XML is constructed is different between our infoset
    // results and the ones created by scala directly parsing the TDML test files.
    //
    // This has something to do with values being lists of text nodes and entities
    // and not just simple strings. I.e., if you write: <foo>a&#x5E74;</foo>, that's not
    // an element with a string as its value. It's an element with several text nodes as
    // its values.
    //
    // so we run the expected stuff through the same converters that were used to
    // convert the actual.
    // val expected = XMLUtils.element2ElemTDML(XMLUtils.elem2ElementTDML(infoset.contents)) //val expected = XMLUtils.element2Elem(XMLUtils.elem2Element(infoset.contents))
    val expected = infoset.contents
    // infoset.contents already has attributes removed.
    // however, we call removeAttributes anyway because of the way it collapses
    // multiple strings within a text node.
    val trimmedExpected = XMLUtils.removeAttributes(Utility.trim(expected))

    XMLUtils.compareAndReport(trimmedExpected, actualNoAttrs)
  }

  def runParseExpectErrors(processor: DFDL.DataProcessor,
    dataToParse: DFDL.Input,
    lengthLimitInBits: Long,
    errors: ExpectedErrors,
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) {

    val diagnostics = {
      if (processor.isError) processor.getDiagnostics
      else {
        val actual = processor.parse(dataToParse, lengthLimitInBits)
        if (actual.isError) actual
        else {
          val loc: DataLocation = actual.resultState.currentLocation

          if (!loc.isAtEnd) {
            actual.addDiagnostic(new GeneralParseFailure("Left over data: " + loc.toString))
            actual
          } else {
            // We did not get an error!!
            // val diags = actual.getDiagnostics().map(_.getMessage()).foldLeft("")(_ + "\n" + _)
            throw new TDMLException("Expected error. Didn't get one. Actual result was " + actual.briefResult) // if you just assertTrue(actual.canProceed), and it fails, you get NOTHING useful.
          }
        }
        processor.getDiagnostics ++ actual.getDiagnostics
      }
    }

    // check for any test-specified errors
    verifyAllDiagnosticsFound(diagnostics, Some(errors))

    // check for any test-specified warnings
    verifyAllDiagnosticsFound(diagnostics, warnings)

  }

  def runParseExpectSuccess(processor: DFDL.DataProcessor,
    dataToParse: DFDL.Input,
    lengthLimitInBits: Long,
    infoset: Infoset,
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) {

    if (processor.isError) {
      val diagObjs = processor.getDiagnostics
      if (diagObjs.length == 1) throw diagObjs(0)
      val diags = diagObjs.map(_.getMessage).mkString("\n")
      throw new TDMLException(diags)
    }
    processor.setValidationMode(validationMode)
    val actual = processor.parse(dataToParse, lengthLimitInBits)

    if (!actual.canProceed) {
      // Means there was an error, not just warnings.
      val diagObjs = actual.getDiagnostics
      if (diagObjs.length == 1) throw diagObjs(0)
      val diags = actual.getDiagnostics.map(_.getMessage).mkString("\n")
      throw new TDMLException(diags) // if you just assertTrue(objectToDiagnose.canProceed), and it fails, you get NOTHING useful.
    }

    validationMode match {
      case ValidationMode.Off => // Don't Validate
      case mode => {
        if (actual.isValidationSuccess) {
          // println("Validation Succeeded!") 
        }
      }
    }

    val loc: DataLocation = actual.resultState.currentLocation

    val leftOverException = if (!loc.isAtEnd) {
      val leftOverMsg = "Left over data: " + loc.toString
      println(leftOverMsg)
      Some(new TDMLException(leftOverMsg))
    } else None

    verifyParseInfoset(actual, infoset)

    (shouldValidate, expectsValidationError) match {
      case (true, true) => verifyAllDiagnosticsFound(actual, validationErrors) // verify all validation errors were found
      case (true, false) => verifyNoValidationErrorsFound(actual) // Verify no validation errors from parser
      case (false, true) => throw new TDMLException("Test case invalid. Validation is off but the test expects an error.")
      case (false, false) => // Nothing to do here.
    }

    leftOverException.map { throw _ } // if we get here, throw the left over data exception.

    // TODO: Implement Warnings
    // check for any test-specified warnings
    //verifyAllDiagnosticsFound(actual, warnings)

    val allDiags = processor.getDiagnostics ++ actual.getDiagnostics
    verifyAllDiagnosticsFound(allDiags, warnings)

    // if we get here, the test passed. If we don't get here then some exception was
    // thrown either during the run of the test or during the comparison.
  }
}

case class UnparserTestCase(ptc: NodeSeq, parentArg: DFDLTestSuite)
  extends TestCase(ptc, parentArg) {

  def runProcessor(processor: DFDL.DataProcessor,
    data: Option[DFDL.Input],
    lengthLimitInBits: Option[Long],
    optInfoset: Option[Infoset],
    optErrors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type): Unit = ???

  def runProcessor(pf: DFDL.ProcessorFactory,
    optData: Option[DFDL.Input],
    optNBits: Option[Long],
    optInfoset: Option[Infoset],
    optErrors: Option[ExpectedErrors],
    warnings: Option[ExpectedWarnings],
    validationErrors: Option[ExpectedValidationErrors],
    validationMode: ValidationMode.Type) = {

    val infoset = optInfoset.get

    (optData, optErrors) match {
      case (Some(data), None) => runUnparserExpectSuccess(pf, data, infoset, warnings)
      case (_, Some(errors)) => runUnparserExpectErrors(pf, optData, infoset, errors, warnings)
      case _ => Assert.invariantFailed("Should be Some None, or None Some only.")
    }

  }

  def verifyData(data: DFDL.Input, outStream: java.io.ByteArrayOutputStream) {
    val actualBytes = outStream.toByteArray

    val inbuf = java.nio.ByteBuffer.allocate(1024 * 1024) // TODO: allow override? Detect overrun?
    val readCount = data.read(inbuf)
    data.close()
    if (readCount == -1) {
      // example data was of size 0 (could not read anything). We're not supposed to get any actual data.
      if (actualBytes.length > 0) {
        throw new TDMLException("Unexpected data was created.")
      }
      return // we're done. Nothing equals nothing.
    }

    Assert.invariant(readCount == inbuf.position())

    // compare expected data to what was output.
    val expectedBytes = inbuf.array().toList.slice(0, readCount)
    if (actualBytes.length != readCount) {
      throw new TDMLException("output data length " + actualBytes.length + " for " + actualBytes.toList +
        " doesn't match expected value " + readCount + " for " + expectedBytes)
    }

    val pairs = expectedBytes zip actualBytes zip Stream.from(1)
    pairs.foreach {
      case ((expected, actual), index) =>
        if (expected != actual) {
          val msg = "Unparsed data differs at byte %d. Expected 0x%02x. Actual was 0x%02x.".format(index, expected, actual)
          throw new TDMLException(msg)
        }
    }
  }

  def runUnparserExpectSuccess(pf: DFDL.ProcessorFactory,
    data: DFDL.Input,
    infoset: Infoset,
    warnings: Option[ExpectedWarnings]) {

    val outStream = new java.io.ByteArrayOutputStream()
    val output = java.nio.channels.Channels.newChannel(outStream)
    val node = infoset.contents
    if (pf.isError) {
      val diags = pf.getDiagnostics.map(_.getMessage).mkString("\n")
      throw new TDMLException(diags)
    }
    val processor = pf.onPath("/")
    if (processor.isError) {
      val diags = processor.getDiagnostics.map(_.getMessage).mkString("\n")
      throw new TDMLException(diags)
    }
    val actual = processor.unparse(output, node)
    output.close()

    verifyData(data, outStream)

    // TODO: Implement Warnings - check for any test-specified warnings
    // verifyAllDiagnosticsFound(actual, warnings)

  }

  def runUnparserExpectErrors(pf: DFDL.ProcessorFactory,
    optData: Option[DFDL.Input],
    infoset: Infoset,
    errors: ExpectedErrors,
    warnings: Option[ExpectedWarnings]) {

    val outStream = new java.io.ByteArrayOutputStream()
    val output = java.nio.channels.Channels.newChannel(outStream)
    val node = infoset.contents
    if (pf.isError) {
      // check for any test-specified errors
      verifyAllDiagnosticsFound(pf, Some(errors))

      // check for any test-specified warnings
      verifyAllDiagnosticsFound(pf, warnings)
    }
    val processor = pf.onPath("/")
    if (processor.isError) {
      val diags = processor.getDiagnostics.map(_.getMessage).mkString("\n")
      throw new TDMLException(diags)
    }
    val actual = processor.unparse(output, node)
    output.close()
    val actualBytes = outStream.toByteArray()

    // Verify that some partial output has shown up in the bytes.
    optData.map { data => verifyData(data, outStream) }

    // check for any test-specified errors
    verifyAllDiagnosticsFound(actual, Some(errors))

    // check for any test-specified warnings
    verifyAllDiagnosticsFound(actual, warnings)

  }

}

case class DefinedSchema(xml: Node, parent: DFDLTestSuite) {
  val name = (xml \ "@name").text.toString

  val defineFormats = (xml \ "defineFormat")
  val defaultFormats = (xml \ "format")
  val defineVariables = (xml \ "defineVariable")
  val defineEscapeSchemes = (xml \ "defineEscapeScheme")

  val globalElementDecls = {
    val res = (xml \ "element")
    res
  }
  val globalSimpleTypeDefs = (xml \ "simpleType")
  val globalComplexTypeDefs = (xml \ "complexType")
  val globalGroupDefs = (xml \ "group")
  val globalIncludes = (xml \ "include")
  val globalImports = (xml \ "import")

  val dfdlTopLevels = defineFormats ++ defaultFormats ++ defineVariables ++ defineEscapeSchemes
  val xsdTopLevels = globalImports ++ globalIncludes ++ globalElementDecls ++ globalSimpleTypeDefs ++
    globalComplexTypeDefs ++ globalGroupDefs
  val fileName = parent.ts.attribute(XMLUtils.INT_NS, XMLUtils.FILE_ATTRIBUTE_NAME) match {
    case Some(seqNodes) => seqNodes.toString
    case None => ""
  }
  lazy val xsdSchema =
    SchemaUtils.dfdlTestSchema(dfdlTopLevels, xsdTopLevels, fileName = fileName, schemaScope = xml.scope)
}

case class DefinedConfig(xml: Node, parent: DFDLTestSuite) {
  val name = (xml \ "@name").text.toString
  val externalVariableBindings = (xml \ "externalVariableBindings").headOption

  // Add additional compiler tunable variables here

  val fileName = parent.ts.attribute(XMLUtils.INT_NS, XMLUtils.FILE_ATTRIBUTE_NAME) match {
    case Some(seqNodes) => seqNodes.toString
    case None => ""
  }
}

sealed abstract class DocumentContentType
case object ContentTypeText extends DocumentContentType
case object ContentTypeByte extends DocumentContentType
case object ContentTypeBits extends DocumentContentType
case object ContentTypeFile extends DocumentContentType
// TODO: add capability to specify character set encoding into which text is to be converted (all UTF-8 currently)

sealed abstract class BitOrderType
case object LSBFirst extends BitOrderType
case object MSBFirst extends BitOrderType

sealed abstract class ByteOrderType
case object RTL extends ByteOrderType
case object LTR extends ByteOrderType

case class Document(d: NodeSeq, parent: TestCase) {
  lazy val documentExplicitBitOrder = (d \ "@bitOrder").toString match {
    case "LSBFirst" => Some(LSBFirst)
    case "MSBFirst" => Some(MSBFirst)
    case "" => None
    case _ => Assert.invariantFailed("invalid bit order.")
  }

  lazy val nDocumentParts = dataDocumentParts.length

  lazy val documentBitOrder: BitOrderType = {
    this.documentExplicitBitOrder match {
      case Some(order) => order
      case None => {
        // analyze the child parts 
        val groups = dataDocumentParts.groupBy(_.explicitBitOrder).map {
          case (key, seq) => (key, seq.length)
        }
        if (groups.get(Some(MSBFirst)) == Some(nDocumentParts)) MSBFirst // all are msb first
        else if (groups.get(Some(LSBFirst)) == Some(nDocumentParts)) LSBFirst // all are lsb first
        else if (groups.get(None) == Some(nDocumentParts)) MSBFirst // everything is silent on bit order.
        else {
          // Some mixture of explicit and non-explicit bitOrder
          Assert.usageError(
            "Must specify bitOrder on document element when parts have a mixture of bit orders.")
        }
      }
    }
  }

  val Seq(<document>{ children @ _* }</document>) = d

  val actualDocumentPartElementChildren = children.toList.flatMap {
    child =>
      child match {
        case <documentPart>{ _* }</documentPart> => {
          List((child \ "@type").toString match {
            case "text" => new TextDocumentPart(child, this)
            case "byte" => new ByteDocumentPart(child, this)
            case "bits" => new BitsDocumentPart(child, this)
            case "file" => new FileDocumentPart(child, this)
            case _ => Assert.invariantFailed("invalid content type.")

          })
        }
        case _ => Nil
      }
  }

  // check that document element either contains text content directly with no other documentPart children, 
  // or it contains ONLY documentPart children (and whitespace around them).
  //
  if (actualDocumentPartElementChildren.length > 0) {
    children.foreach { child =>
      child match {
        case <documentPart>{ _* }</documentPart> => // ok
        case scala.xml.Text(s) if (s.matches("""\s+""")) => // whitespace text nodes ok
        case scala.xml.Comment(_) => // ok
        case scala.xml.PCData(s) => // ok
        case scala.xml.EntityRef(_) => //ok
        case _: scala.xml.Atom[_] => //ok. Things like &lt; come through as this. Should be EntityRef("lt")
        case x => Assert.usageError("Illegal TDML data document content '" + x + "'")
      }
    }
  }

  lazy val unCheckedDocumentParts: Seq[DocumentPart] = {
    if (actualDocumentPartElementChildren.length > 0) actualDocumentPartElementChildren
    else List(new TextDocumentPart(<documentPart type="text">{ children }</documentPart>, this))
  }
  lazy val dataDocumentParts = {
    val dps = unCheckedDocumentParts.collect { case dp: DataDocumentPart => dp }
    dps
  }

  lazy val fileParts = {
    val fps = unCheckedDocumentParts.collect { case fp: FileDocumentPart => fp }
    Assert.usage(fps.length == 0 ||
      (fps.length == 1 && dataDocumentParts.length == 0),
      "There can be only one documentPart of type file, and it must be the only documentPart.")
    fps
  }

  lazy val documentParts = {
    checkForBadBitOrderTransitions(dataDocumentParts)
    dataDocumentParts ++ fileParts
  }

  /**
   * A method because it is easier to unit test it
   */
  def checkForBadBitOrderTransitions(dps: Seq[DataDocumentPart]) {
    if (dps.length <= 1) return
    // these are the total lengths BEFORE the component
    val lengths = dps.map { _.lengthInBits }
    val cumulativeDocumentPartLengthsInBits = lengths.scanLeft(0) { case (sum, num) => sum + num }
    val docPartBitOrders = dps.map { _.partBitOrder }
    val transitions = docPartBitOrders zip docPartBitOrders.tail zip cumulativeDocumentPartLengthsInBits.tail zip dps
    transitions.foreach {
      case (((bitOrderPrior, bitOrderHere), cumulativeLength), docPart) => {
        // println("transition " + bitOrderPrior + " " + bitOrderHere + " " + cumulativeLength)
        Assert.usage(
          (bitOrderPrior == bitOrderHere) || ((cumulativeLength % 8) == 0),
          "bitOrder can only change on a byte boundary.")
      }
    }
  }

  /**
   * When data is coming from the TDML file as small test data in
   * DataDocumentParts, then
   * Due to alignment, and bits-granularity issues, everything is lowered into
   * bits first, and then concatenated, and then converted back into bytes
   *
   * These are all lazy val, since if data is coming from a file these aren't
   * needed at all.
   */
  lazy val documentBits = {
    val nFragBits = (nBits.toInt % 8)
    val nAddOnBits = if (nFragBits == 0) 0 else 8 - nFragBits
    val addOnBits = "0" * nAddOnBits
    val bitsFromParts = dataDocumentParts.map { _.contentAsBits }
    val allPartsBits = documentBitOrder match {
      case MSBFirst => bitsFromParts.flatten
      case LSBFirst => {
        val x = bitsFromParts.map { _.map { _.reverse } }
        val rtlBits = x.flatten.mkString.reverse
        val ltrBits = rtlBits.reverse.sliding(8, 8).map { _.reverse }.toList
        ltrBits
      }
    }
    val allBits = allPartsBits.mkString.sliding(8, 8).toList
    if (allBits == Nil) Nil
    else {
      val lastByte = this.documentBitOrder match {
        case MSBFirst => allBits.last + addOnBits
        case LSBFirst => addOnBits + allBits.last
      }
      val res = allBits.dropRight(1) :+ lastByte
      res
    }
  }

  lazy val nBits: Long = documentParts.map { _.nBits } sum

  lazy val documentBytes = bits2Bytes(documentBits)

  /**
   * this 'data' is the kind our parser's parse method expects.
   * Note: this is def data so that the input is re-read every time.
   * Needed if you run the same test over and over.
   */
  def data = {
    if (isDPFile) {
      // direct I/O to the file. No 'bits' lowering involved. 
      val dp = documentParts(0).asInstanceOf[FileDocumentPart]
      val input = dp.fileDataInput
      input
    } else {
      // assemble the input from the various pieces, having lowered
      // everything to bits.
      val bytes = documentBytes.toArray
      // println("data size is " + bytes.length)
      val inputStream = new java.io.ByteArrayInputStream(bytes);
      val rbc = java.nio.channels.Channels.newChannel(inputStream);
      rbc.asInstanceOf[DFDL.Input]
    }
  }

  /**
   * data coming from a file?
   */
  val isDPFile = {
    val res = documentParts.length > 0 &&
      documentParts(0).isInstanceOf[FileDocumentPart]
    if (res) {
      Assert.usage(documentParts.length == 1, "There can be only one documentPart of type file, and it must be the only documentPart.")
    }
    res
  }

}

class TextDocumentPart(part: Node, parent: Document) extends DataDocumentPart(part, parent) {

  lazy val encoder = {
    if (encodingName.toUpperCase == "US-ASCII-7-BIT-PACKED")
      Assert.usage(partBitOrder == LSBFirst, "encoding US-ASCII-7-BIT-PACKED requires bitOrder='LSBFirst'")
    CharsetUtils.getCharset(encodingName).newEncoder()
  }

  lazy val textContentWithoutEntities = {
    if (replaceDFDLEntities) {
      try { EntityReplacer { _.replaceAll(partRawContent) } }
      catch { case (e: Exception) => Assert.abort(e.getMessage()) }
    } else partRawContent
  }

  /**
   * Result is sequence of strings, each string representing a byte or
   * partial byte using '1' and '0' characters for the bits.
   */
  def encodeUtf8ToBits(s: String): Seq[String] = {
    // Fails here if we use getBytes("UTF-8") because that uses the utf-8 encoder,
    // and that will fail on things like unpaired surrogate characters that we allow
    // in our data and our infoset.
    // So instead we must do our own UTF-8-like encoding of the data
    // so that we can put in codepoints we want. 
    val bytes = UTF8Encoder.utf8LikeEncode(textContentWithoutEntities).toArray
    val res = bytes.map { b => (b & 0xFF).toBinaryString.reverse.padTo(8, '0').reverse }.toList
    res
  }

  def encodeWith7BitEncoder(s: String): Seq[String] = {
    val bb = ByteBuffer.allocate(4 * s.length)
    val cb = CharBuffer.wrap(s)
    val coderResult = encoder.encode(cb, bb, true)
    Assert.invariant(coderResult == CoderResult.UNDERFLOW)
    bb.flip()
    val res = (0 to bb.limit() - 1).map { bb.get(_) }
    val bitsAsString = bytes2Bits(res.toArray)
    val enc = encoder.asInstanceOf[NonByteSizeCharsetEncoderDecoder]
    val nBits = s.length * enc.widthOfACodeUnit
    val bitStrings = res.map { b => (b & 0xFF).toBinaryString.reverse.padTo(8, '0').reverse }.toList
    val allBits = bitStrings.reverse.mkString.takeRight(nBits)
    val sevenBitChunks = allBits.reverse.sliding(7, 7).map { _.reverse }.toList
    sevenBitChunks
  }

  def encodeWith8BitEncoder(s: String): Seq[String] = {
    val bb = ByteBuffer.allocate(4 * s.length)
    val cb = CharBuffer.wrap(s)
    val coderResult = encoder.encode(cb, bb, true)
    Assert.invariant(coderResult == CoderResult.UNDERFLOW)
    bb.flip()
    val res = (0 to bb.limit() - 1).map { bb.get(_) }
    val bitsAsString = bytes2Bits(res.toArray)
    val nBits = bb.limit() * 8
    val bitStrings = res.map { b => (b & 0xFF).toBinaryString.reverse.padTo(8, '0').reverse }.toList
    bitStrings
  }

  lazy val dataBits = {
    val bytesAsStrings =
      if (encoder.charset.name.toLowerCase == "utf-8")
        encodeUtf8ToBits(textContentWithoutEntities)
      else if (encodingName.toUpperCase == "US-ASCII-7-BIT-PACKED")
        encodeWith7BitEncoder(textContentWithoutEntities)
      else encodeWith8BitEncoder(textContentWithoutEntities)
    bytesAsStrings
  }
}

class ByteDocumentPart(part: Node, parent: Document) extends DataDocumentPart(part, parent) {
  val validHexDigits = "0123456789abcdefABCDEF"

  lazy val dataBits = {
    val hexBytes = partByteOrder match {
      case LTR => {
        val ltrDigits = hexDigits.sliding(2, 2).toList
        ltrDigits
      }
      case RTL => {
        val rtlDigits = hexDigits.reverse.sliding(2, 2).toList.map { _.reverse }
        rtlDigits
      }
    }
    val bits = hexBytes.map { hex2Bits(_) }
    bits
  }

  // Note: anything that is not a valid hex digit (or binary digit for binary) is simply skipped
  // TODO: we should check for whitespace and other characters we want to allow, and verify them.
  // TODO: Or better, validate this in the XML Schema for tdml via a pattern facet
  // TODO: Consider whether to support a comment syntax. When showing data examples this may be useful.
  //
  lazy val hexDigits = partRawContent.flatMap { ch => if (validHexDigits.contains(ch)) List(ch) else Nil }

}
class BitsDocumentPart(part: Node, parent: Document) extends DataDocumentPart(part, parent) {
  // val validBinaryDigits = "01"

  // lazy val bitContentToBytes = bits2Bytes(bitDigits).toList

  lazy val bitDigits = {
    val res = partRawContent.split("[^01]").mkString
    res
  }

  lazy val dataBits = partByteOrder match {
    case LTR => {
      val ltrBigits = bitDigits.sliding(8, 8).toList
      ltrBigits
    }
    case RTL => {
      val rtlBigits =
        bitDigits.reverse.sliding(8, 8).toList.map { _.reverse }
      rtlBigits
    }
  }
}

class FileDocumentPart(part: Node, parent: Document) extends DocumentPart(part, parent) with Logging {

  override lazy val nBits = -1L // signifies we do not know how many.

  lazy val fileDataInput = {
    val maybeURI = parent.parent.parent.findTDMLResource(partRawContent.trim())
    val uri = maybeURI.getOrElse(throw new FileNotFoundException("TDMLRunner: data file '" + partRawContent + "' was not found"))
    val url = uri.toURL
    if (url.getProtocol() == "file") {
      val file = new File(uri)
      log(LogLevel.Debug, "File size is %s", file.length())
    }
    val is = url.openStream()
    val rbc = Channels.newChannel(is)
    rbc.asInstanceOf[DFDL.Input]
  }

}

/**
 * Base class for all document parts that contain data directly expressed in the XML
 */
sealed abstract class DataDocumentPart(part: Node, parent: Document)
  extends DocumentPart(part, parent) {

  def dataBits: Seq[String]

  lazy val lengthInBits = dataBits.map { _.length } sum
  override lazy val nBits: Long = lengthInBits

  lazy val contentAsBits = dataBits

}

/**
 * Base class for all document parts
 */
sealed abstract class DocumentPart(part: Node, parent: Document) {

  def nBits: Long

  lazy val explicitBitOrder: Option[BitOrderType] = {
    val bitOrd = (part \ "@bitOrder").toString match {

      case "LSBFirst" => Some(LSBFirst)
      case "MSBFirst" => Some(MSBFirst)
      case "" => None
      case _ => Assert.invariantFailed("invalid bit order.")
    }
    Assert.usage(!isInstanceOf[FileDocumentPart],
      "bitOrder may not be specified on document parts of type 'file'")
    bitOrd
  }

  lazy val partBitOrder = explicitBitOrder.getOrElse(parent.documentBitOrder)

  lazy val partByteOrder = {
    val bo = (part \ "@byteOrder").toString match {
      case "RTL" => {
        Assert.usage(partBitOrder == LSBFirst, "byteOrder RTL can only be used with bitOrder LSBFirst")
        RTL
      }
      case "LTR" => LTR
      case "" => LTR
      case _ => Assert.invariantFailed("invalid byte order.")
    }
    Assert.usage(this.isInstanceOf[ByteDocumentPart] || this.isInstanceOf[BitsDocumentPart],
      "byteOrder many only be specified for document parts of type 'byte' or 'bits'")
    bo
  }

  /**
   * Only trim nodes that aren't PCData (aka <![CDATA[...]]>)
   */
  lazy val trimmedParts = part.child flatMap { childNode =>
    childNode match {
      case scala.xml.PCData(s) => Some(childNode)
      case scala.xml.Text(s) => {
        // can't just use s.trim here as that would remove explicit
        // carriage returns like &#x0D; if they have already been 
        // replaced by the corresponding character.
        val trimmedEnd = s.replaceFirst("\\ +$", "") // spaces only
        val trimmed = trimmedEnd.replaceFirst("^\\ +", "") // spaces only
        if (trimmed.length == 0) None
        else Some(scala.xml.Text(trimmed))
      }
      case scala.xml.Comment(_) => None
      case scala.xml.EntityRef(_) => Some(childNode)
      case _: scala.xml.Atom[_] => Some(childNode) // Things like &lt; come through as this. Should be EntityRef
      case _ => Assert.invariantFailed("unrecognized child part in TextDocumentPart: " + childNode)
    }
  }

  lazy val partRawContent = trimmedParts.text

  lazy val replaceDFDLEntities: Boolean = {
    val res = (part \ "@replaceDFDLEntities")
    if (res.length == 0) { false }
    else {
      Assert.usage(this.isInstanceOf[TextDocumentPart])
      res(0).toString().toBoolean
    }
  }

  lazy val encodingName: String = {
    val res = (part \ "@encoding").text
    if (res.length == 0) { "utf-8" }
    else {
      Assert.usage(this.isInstanceOf[TextDocumentPart])
      res
    }
  }

}

case class Infoset(i: NodeSeq, parent: TestCase) {
  lazy val Seq(dfdlInfoset) = (i \ "dfdlInfoset").map { node => new DFDLInfoset(Utility.trim(node), this) }
  lazy val contents = dfdlInfoset.contents
}

case class DFDLInfoset(di: Node, parent: Infoset) {
  lazy val children = di.child.filterNot { _.isInstanceOf[scala.xml.Comment] }
  lazy val Seq(contents) = {
    Assert.usage(children.size == 1, "dfdlInfoset element must contain a single root element")

    val c = children(0)
    val expected = Utility.trim(c) // must be exactly one root element in here.
    val expectedNoAttrs = XMLUtils.removeAttributes(expected)
    //
    // Let's validate the expected content against the schema
    // Just to be sure they don't drift.
    //
    //    val ptc = parent.parent
    //    val schemaNode = ptc.findModel(ptc.model)
    //
    // This is causing trouble, with the stripped attributes, etc.
    // TODO: Fix so we can validate these expected results against
    // the DFDL schema used as a XSD for the expected infoset XML.
    //
    expectedNoAttrs
  }
}

abstract class ErrorWarningBase(n: NodeSeq, parent: TestCase) {
  lazy val matchAttrib = (n \ "@match").text
  protected def diagnosticNodes: Seq[Node]
  lazy val messages = diagnosticNodes.map { _.text }

  def hasDiagnostics: Boolean = diagnosticNodes.length > 0
}

case class ExpectedErrors(node: NodeSeq, parent: TestCase)
  extends ErrorWarningBase(node, parent) {

  val diagnosticNodes = node \\ "error"

}

case class ExpectedWarnings(node: NodeSeq, parent: TestCase)
  extends ErrorWarningBase(node, parent) {

  val diagnosticNodes = node \\ "warning"

}

case class ExpectedValidationErrors(node: NodeSeq, parent: TestCase)
  extends ErrorWarningBase(node, parent) {

  val diagnosticNodes = node \\ "error"

}

object UTF8Encoder {
  def utf8LikeEncode(s: String): Seq[Byte] = {
    // 
    // Scala/Java strings represent characters above 0xFFFF as a surrogate pair
    // of two codepoints. 
    //
    // We want to handle both properly match surrogate pairs, and isolated surrogate characters.
    // That means if we see an isolated low (second) surrogate character, we have to know 
    // whether it was preceded by a high surrogate or not.
    // 
    // For every 16-bit code point, do do this right we need to potentially also see the previous
    // or next codepoint.
    //
    val bytes = XMLUtils.walkUnicodeString(s)(utf8LikeEncoding).flatten
    // val bytes = tuples.flatMap { case ((prevcp, cp), nextcp) => utf8LikeEncoding(prevcp, cp, nextcp) }
    bytes
  }

  def byteList(args: Int*) = args.map { _.toByte }

  /**
   * Encode in the style of utf-8 (see wikipedia article on utf-8)
   *
   * Variation is that we accept some things that a conventional utf-8 encoder
   * rejects. Examples are illegal codepoints such as isolated Unicode surrogates
   * (not making up a surrogate pair).
   *
   * We also assume we're being handed surrogate pairs for any of the
   * 4-byte character representations.
   *
   */

  def utf8LikeEncoding(prev: Char, c: Char, next: Char): Seq[Byte] = {
    // handles 16-bit codepoints only
    Assert.usage(prev <= 0xFFFF)
    Assert.usage(c <= 0xFFFF)
    Assert.usage(next <= 0xFFFF)

    val i = c.toInt
    val byte1 = ((i >> 8) & 0xFF)
    val byte2 = (i & 0xFF)

    def threeByteEncode() = {
      val low6 = byte2 & 0x3F
      val mid6 = ((byte1 & 0x0F) << 2) | (byte2 >> 6)
      val high4 = byte1 >> 4
      byteList(high4 | 0xE0, mid6 | 0x80, low6 | 0x80)
    }

    /**
     * create 4-byte utf-8 encoding from surrogate pair found
     * in a scala string.
     */
    def fourByteEncode(leadingSurrogate: Char, trailingSurrogate: Char) = {
      val h = leadingSurrogate.toInt // aka 'h for high surrogate'
      val l = trailingSurrogate.toInt // aka 'l for low surrogate'
      val cp = 0x10000 + ((h - 0xD800) * 0x400) + (l - 0xDC00)
      val byte1 = (cp >> 24) & 0xFF
      val byte2 = (cp >> 16) & 0xFF
      val byte3 = (cp >> 8) & 0xFF
      val byte4 = cp & 0xFF
      val low6 = byte4 & 0x3F
      val midlow6 = ((byte3 & 0x0F) << 2) | (byte4 >> 6)
      val midhig6 = ((byte2 & 0x03) << 4) | byte3 >> 4
      val high3 = byte2 >> 2
      byteList(high3 | 0xF0, midhig6 | 0x80, midlow6 | 0x80, low6 | 0x80)
    }

    val res = i match {
      case _ if (i <= 0x7F) => byteList(byte2)
      case _ if (i <= 0x7FF) => {
        val low6 = byte2 & 0x3F
        val high5 = ((byte1 & 0x07) << 2) | (byte2 >> 6)
        byteList(high5 | 0xC0, low6 | 0x80)
      }
      case _ if (XMLUtils.isLeadingSurrogate(c)) => {
        // High (initial) Surrogate character case.
        if (XMLUtils.isTrailingSurrogate(next)) {
          // Next codepoint is a low surrogate.
          // We need to create a 4-byte representation from the
          // two surrogate characters.
          fourByteEncode(c, next)
        } else {
          // isolated high surrogate codepoint case.
          threeByteEncode()
        }
      }
      case _ if (XMLUtils.isTrailingSurrogate(c)) => {
        // Low (subsequent) Surrogate character case.
        if (XMLUtils.isLeadingSurrogate(prev)) {
          // Previous codepoint was a high surrogate. 
          // This codepoint was handled as part of converting the
          // surrogate pair.
          // so we output no bytes at all.
          List()
        } else {
          // Isolated low-surrogate codepoint case.
          threeByteEncode()
        }

      }
      case _ if (i <= 0xFFFF) => {
        threeByteEncode()
      }

      case _ => Assert.invariantFailed("char code out of range.")
    }
    res
  }

}
