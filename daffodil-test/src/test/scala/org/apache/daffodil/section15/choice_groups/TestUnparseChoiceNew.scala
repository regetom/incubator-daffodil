/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.daffodil.section15.choice_groups

import org.junit.Test
import org.junit.AfterClass
import org.apache.daffodil.util._
import org.apache.daffodil.tdml.DFDLTestSuite

object TestUnparseChoiceNew {
  val testDir = "/org/apache/daffodil/section15/choice_groups/"
  val aa = testDir + "choice-unparse.tdml"
  var runnerCH = new DFDLTestSuite(Misc.getRequiredResource(aa))

  @AfterClass def tearDown(): Unit = {
    runnerCH = null
  }
}

class TestUnparseChoiceNew {
  import TestUnparseChoiceNew._

  @Test def test_choice7(): Unit = { runnerCH.runOneTest("choice7") }
  @Test def test_choice8(): Unit = { runnerCH.runOneTest("choice8") }
  @Test def test_choice9(): Unit = { runnerCH.runOneTest("choice9") }
  @Test def test_choice10(): Unit = { runnerCH.runOneTest("choice10") }
}
