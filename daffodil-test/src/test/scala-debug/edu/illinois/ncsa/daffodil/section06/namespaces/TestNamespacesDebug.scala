package edu.illinois.ncsa.daffodil.section06.namespaces

/* Copyright (c) 2012-2013 Tresys Technology, LLC. All rights reserved.
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

import junit.framework.Assert._
import org.junit.Test
import scala.xml._
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.xml.XMLUtils._
import edu.illinois.ncsa.daffodil.compiler.Compiler
import edu.illinois.ncsa.daffodil.util._
import edu.illinois.ncsa.daffodil.tdml.DFDLTestSuite
import java.io.File
import edu.illinois.ncsa.daffodil.debugger.Debugger

class TestNamespacesDebug {
  val testDir = "/edu/illinois/ncsa/daffodil/section06/namespaces/"
  val aa = testDir + "namespaces.tdml"
  lazy val runner = new DFDLTestSuite(Misc.getRequiredResource(aa))

  // This test should get an SDE because the delimiter (sequence separator) is not the same encoding 
  // as the element that will be terminated by that delimiter.
  @Test def test_multi_encoding_02() = // Debugger.withDebugger 
    { runner.runOneTest("multi_encoding_02") }

  @Test def test_multi_encoding_04() { runner.runOneTest("multi_encoding_04") }

  @Test def test_namespace_ultra_uniqueness_03() { runner.runOneTest("namespace_ultra_uniqueness_03") }

  // Failed after implementation of DFDL-336.  Appears to be due to
  // allParentTerminatingMarkup. See created issue DFDL-571
  @Test def test_long_chain_05() { runner.runOneTest("long_chain_05") }
 
  //Getting "length of string must be exactly 1 character or be empty" error seen in DFDL-562
  @Test def test_namespaces_qnames3() { runner.runOneTest("namespaces_qnames3") }

}
