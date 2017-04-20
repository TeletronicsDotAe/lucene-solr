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
 * VersionConflict indicates a conflict in version between a document sent for update and the version currently in store. This basically means
 * that the document in Solr has changes since the document now sent for update was loaded, and that those changes will be overwritten if we
 * just store the document sent for update. The client should react by reloading the document from Solr, merge his changes into this and resend
 * for update.
 */
public class VersionConflict extends DocumentUpdateBaseException {

	private static final long serialVersionUID = 1L;
	
	protected static final String CURRENT_VERSION_PROPERTIES_KEY = "currentVersion";

	public VersionConflict(ErrorCode code, String msg) {
    super(code, msg);
  }
  public VersionConflict(ErrorCode code, String msg, Throwable th) {
    super(code, msg, th);
  }
  
  public void setCurrentVersion(long currentVersion) {
    setProperty(CURRENT_VERSION_PROPERTIES_KEY, currentVersion);
  }
  
  public long getCurrentVersion() {
    return (Long)getProperty(CURRENT_VERSION_PROPERTIES_KEY); 
  }

}
