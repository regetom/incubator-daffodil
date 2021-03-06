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
package org.badudfs.annotations.StringFunctions;

import org.apache.daffodil.udf.UserDefinedFunction;
import org.apache.daffodil.udf.UserDefinedFunctionIdentification;

/**
 * UDF for Annotations Negative Unit test
 *
 * Contains annotation with blank fields
 */
@SuppressWarnings("serial")
@UserDefinedFunctionIdentification(
    name = "",
    namespaceURI = "")
public class Compare implements UserDefinedFunction {
  public Boolean evaluate(String str1, String str2) {
    Boolean ret = false;
    ret = str1.contentEquals(str2);
    return ret;
  }
}
