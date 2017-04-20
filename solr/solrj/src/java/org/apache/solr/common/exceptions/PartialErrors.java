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

package org.apache.solr.common.exceptions;

import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;

/**
 * PartialErrors is a request level exception indicating that some parts of a multi-part request failed with partial errors (other parts
 * might have succeeded), and that the client doing the request will need to check the response for what parts succeeded and what parts failed. 
 */
public class PartialErrors extends SolrException {

  private static final long serialVersionUID = 1L;
	
  private SolrResponse specializedResponse;

  public PartialErrors(ErrorCode code, String msg) {
    super(code, msg);
  }
  public PartialErrors(ErrorCode code, String msg, Throwable th) {
    super(code, msg, th);
  }
  
  public void setPayload(NamedList<Object> payload) {
    this.payload.addAll(payload);
  }

	public SolrResponse getSpecializedResponse() {
		return specializedResponse;
	}
	
	public void setSpecializedResponse(SolrResponse specializedResponse) {
		this.specializedResponse = specializedResponse;
	}
	
  @Override
  public boolean worthRetrying() {
    return false;
  }

}
