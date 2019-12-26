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
import org.apache.daffodil.tdml.Runner

object TestUnparseChoice2 {
  val testDir = "/org/apache/daffodil/section15/choice_groups/"

  val runner = Runner(testDir, "choice-unparse2.tdml")

  @AfterClass def tearDown() {
    runner.reset
  }
}

class TestUnparseChoice2 {
  import TestUnparseChoice2._

  // DAFFODIL-2259
  @Test def test_choice_with_array_branch1() { runner.runOneTest("choice_with_array_branch1") }
  @Test def test_choice_with_array_branch2() { runner.runOneTest("choice_with_array_branch2") }
  @Test def test_choice_with_array_branch3() { runner.runOneTest("choice_with_array_branch3") }
}