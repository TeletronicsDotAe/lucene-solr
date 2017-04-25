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
package org.apache.solr.client.solrj;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.exceptions.PartialErrors;
import org.apache.solr.common.util.NamedList;

/**
 * 
 * 
 * @since solr 1.3
 */
public abstract class SolrResponse implements Serializable {
  public static final String PARTIAL_ERRORS_KEY = "partialerrors";
  public static final String HANDLED_PARTS_KEY = "handledParts";
  public static final String PARTIAL_ERROR_PARTREF_KEY = "partRef";
	
  public static final String HTTP_HEADER_KEY_PREFIX = "X-solr-";
	
  protected Map<String, SolrException> partsRefToPartialErrorMap = new HashMap<String, SolrException>();
  protected List<String> handledPartsRef;
	
  public abstract long getElapsedTime();
  
  public abstract void setResponse(NamedList<Object> rsp);

  public abstract void setElapsedTime(long elapsedTime);
  
  public abstract NamedList<Object> getResponse();

  public List<String> getHandledPartsRef() {
    if (handledPartsRef == null) {
      handledPartsRef = getHandledPartsRef(getResponse());
    }
    return handledPartsRef;
  }

  public static List<String> getHandledPartsRef(NamedList<Object> parentNamedList) {
    return (List<String>)parentNamedList.get(SolrResponse.HANDLED_PARTS_KEY);
  }

  public SolrException getPartialError(String partRef) {
    return getPartialErrors(partsRefToPartialErrorMap, getResponse()).get(partRef);
  }

  public static SolrException getPartialError(Map<String, SolrException> localPartialErrosMap, NamedList<Object> parentNamedList, String partRef) {
    localPartialErrosMap = getPartialErrors(localPartialErrosMap, parentNamedList);
    return localPartialErrosMap.get(partRef);
  }

  public Map<String, SolrException> getPartialErrors() {
    return getPartialErrors(partsRefToPartialErrorMap, getResponse());
  }

  public static Map<String, SolrException> getPartialErrors(Map<String, SolrException> localPartialErrorsMap, NamedList<Object> parentNamedList) {
    if (localPartialErrorsMap == null) localPartialErrorsMap = new HashMap<String, SolrException>();
    List<NamedList<Object>> partialErrors = (List<NamedList<Object>>)parentNamedList.get(SolrResponse.PARTIAL_ERRORS_KEY);
    if (partialErrors != null) {
      for (NamedList<Object> partialError : partialErrors) {
        String partRef = (String)partialError.get(SolrResponse.PARTIAL_ERROR_PARTREF_KEY);
        if (!localPartialErrorsMap.containsKey(partRef)) {
          localPartialErrorsMap.put(partRef, SolrException.decodeFromNamedList(partialError));
        }
      }
    }
    return localPartialErrorsMap;
  }

  public void addPartialError(String partRef, SolrException err) {
    addPartialError(partsRefToPartialErrorMap, getResponse(), partRef, err);
  }

  public static void addPartialError(Map<String, SolrException> localPartialErrorsMap, NamedList<Object> parentNamedList, String partRef, SolrException err) {
    if (localPartialErrorsMap != null) {
      err.getPayload().add(SolrResponse.PARTIAL_ERROR_PARTREF_KEY, partRef);
      localPartialErrorsMap.put(partRef, err);
    }
    List<NamedList<Object>> partialErrors = (List<NamedList<Object>>)parentNamedList.get(SolrResponse.PARTIAL_ERRORS_KEY);
    if (partialErrors == null) {
      partialErrors = new ArrayList<NamedList<Object>>();
      parentNamedList.add(SolrResponse.PARTIAL_ERRORS_KEY, partialErrors);
    }
    NamedList<Object> partialError = err.encodeInNamedList();
    partialError.add(SolrResponse.PARTIAL_ERROR_PARTREF_KEY, partRef);
    partialErrors.add(partialError);
  }

  public void addHandledPart(String partRef) {
    addHandledPart(getResponse(), partRef);
  }

  public static void addHandledPart(NamedList<Object> parentNamedList, String partRef) {
    List<String> handledPartsRef;
    if ((handledPartsRef = getHandledPartsRef(parentNamedList)) == null) {
      handledPartsRef = new ArrayList<String>();
      parentNamedList.add(SolrResponse.HANDLED_PARTS_KEY, handledPartsRef);
    }
    if (!handledPartsRef.contains(partRef)) {
      handledPartsRef.add(partRef);
    }
  }

  public void removeAllPartsRef() {
    removeAllPartsRef(partsRefToPartialErrorMap, getResponse());
  }

  public static void removeAllPartsRef(Map<String, SolrException> localPartialErrorsMap, NamedList<Object> parentNamedList) {
    if (localPartialErrorsMap != null) localPartialErrorsMap.clear();
    parentNamedList.remove(SolrResponse.PARTIAL_ERRORS_KEY);
    parentNamedList.remove(SolrResponse.HANDLED_PARTS_KEY);
  }

  public int numberOfPartialErrors() {
    return numberOfPartialErrors(partsRefToPartialErrorMap, getResponse());
  }

  public static int numberOfPartialErrors(Map<String, SolrException> localPartialErrosMap, NamedList<Object> parentNamedList) {
    return getPartialErrors(localPartialErrosMap, parentNamedList).size();
  }

  public Exception getException(boolean cleanContent) {
    return getException(partsRefToPartialErrorMap, getResponse(), cleanContent);
  }

  public static SolrException getException(Map<String, SolrException> localPartialErrorsMap, NamedList<Object> parentNamedList, boolean cleanContent) {
    @SuppressWarnings("unchecked")
    // If only on part of request handled and it resulted in error, throw corresponding exception for convenience
        SolrException singlePartialError;
    List<String> handledPartsRef = SolrResponse.getHandledPartsRef(parentNamedList);
    if ((handledPartsRef != null) && handledPartsRef.size() == 1 && (singlePartialError = SolrResponse.getPartialError(localPartialErrorsMap, parentNamedList, handledPartsRef.iterator().next())) != null) {
      if (cleanContent) SolrResponse.removeAllPartsRef(localPartialErrorsMap, parentNamedList);
      return singlePartialError;
    } else if (SolrResponse.numberOfPartialErrors(localPartialErrorsMap, parentNamedList) > 0) {
      PartialErrors pes = new PartialErrors(ErrorCode.PRECONDITION_FAILED, "Some parts of the request resulted in errors - other parts might have succeeded. Client needs to check response for partial errors");
      pes.setPayload(parentNamedList);
      return pes;
    }
    return null;
  }

  public static byte[] serializable(SolrResponse response) {
    try {
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
      ObjectOutputStream outputStream = new ObjectOutputStream(byteStream);
      outputStream.writeObject(response);
      return byteStream.toByteArray();
    } catch (Exception e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }
  }

  public static SolrResponse deserialize(byte[] bytes) {
    try {
      ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
      ObjectInputStream inputStream = new ObjectInputStream(byteStream);
      return (SolrResponse) inputStream.readObject();
    } catch (Exception e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }
  }
}
