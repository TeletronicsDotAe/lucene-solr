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
 * DocumentDoesNotExist indicates that a document, corresponding to a document now sent for update, does not (any longer) exist in Solr.
 * Client should react by one of the following
 * - Ignoring the error, because he does not want to restore a document he has updated, but that someone else has decided to remove in the meantime
 * - Change the document sent for update to contain version -1 in order to indicate that he want the document created instead of stored as an update to an
 * existing document
 */
public class DocumentDoesNotExist extends DocumentUpdateBaseException {

	private static final long serialVersionUID = 1L;

	public DocumentDoesNotExist(ErrorCode code, String msg) {
    super(code, msg);
  }
  public DocumentDoesNotExist(ErrorCode code, String msg, Throwable th) {
    super(code, msg, th);
  }

}
