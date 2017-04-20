/**
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

package org.apache.solr.common.exceptions.update;

/**
 * DocumentAlreadyExists indicates that a document already exist, when new document is sent for insertion. The client should react by one of the following
 * - Load the document already stored, merge his additions to that and send it for update
 * - Find another unique key for his new document to be inserted, and send for update again
 */
public class DocumentAlreadyExists extends DocumentUpdateBaseException {

	private static final long serialVersionUID = 1L;

	public DocumentAlreadyExists(ErrorCode code, String msg) {
    super(code, msg);
  }
  public DocumentAlreadyExists(ErrorCode code, String msg, Throwable th) {
    super(code, msg, th);
  }

}
