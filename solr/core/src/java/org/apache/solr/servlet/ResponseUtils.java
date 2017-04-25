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
package org.apache.solr.servlet;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;

import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.exceptions.PartialErrors;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.QueryResponseWriterUtil;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.servlet.cache.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Response helper methods.
 */
public class ResponseUtils {

  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final Logger log = LoggerFactory.getLogger(ResponseUtils.class);

  private ResponseUtils() {}

  public static void writeResponse(SolrQueryResponse solrRsp,
      ServletResponse response, QueryResponseWriter responseWriter,
      SolrQueryRequest solrReq, Method reqMethod) throws IOException {
    writeResponse(solrRsp, response, null, responseWriter, solrReq, reqMethod);
  }

  public static void writeResponse(SolrQueryResponse solrRsp,
      OutputStream out, QueryResponseWriter responseWriter,
      SolrQueryRequest solrReq, Method reqMethod) throws IOException {
    writeResponse(solrRsp, null, out, responseWriter, solrReq, reqMethod);
  }

  public static void writeResponse(SolrQueryResponse solrRsp,
      ServletResponse response, OutputStream out, QueryResponseWriter responseWriter,
      SolrQueryRequest solrReq, Method reqMethod) throws IOException {
    boolean sendResponse = true;
    Exception ex;
    if ((ex = solrRsp.getException()) != null) {
      sendResponse = sendError((HttpServletResponse) response, ex, solrRsp);
    }
    if (sendResponse) {
      // Value "yes" is not supposed to be used on receiver side - just check if
      // the header is there or not, but
      // the header will not be included with null-value
      if (response != null) ((HttpServletResponse) response).addHeader(
          HttpSolrClient.HTTP_EXPLICIT_BODY_INCLUDED_HEADER_KEY, "yes");

      try {
        Object invalidStates = solrReq.getContext().get(CloudSolrClient.STATE_VERSION);
        //This is the last item added to the response and the client would expect it that way.
        //If that assumption is changed , it would fail. This is done to avoid an O(n) scan on
        // the response for each request
        if(invalidStates != null) solrRsp.add(CloudSolrClient.STATE_VERSION, invalidStates);
        // Now write it out
        final String ct = responseWriter.getContentType(solrReq, solrRsp);
        // don't call setContentType on null
        if (response != null && null != ct) response.setContentType(ct);

        if (Method.HEAD != reqMethod) {
          QueryResponseWriterUtil.writeQueryResponse((response != null)?response.getOutputStream():out, responseWriter, solrReq, solrRsp, ct);
        }
        //else http HEAD request, nothing to write out, waited this long just to get ContentType
      }
      catch (EOFException e) {
        log.info("Unable to write response, client closed connection or we are shutting down", e);
      }
    }
  }

  public static void sendError(HttpServletResponse response, int code, String message) throws IOException {
    try {
      response.sendError(code, message);
    }
    catch (EOFException e) {
      log.info("Unable to write error response, client closed connection or we are shutting down", e);
    }
  }

  public static boolean sendError(HttpServletResponse response, Throwable ex,
      SolrQueryResponse solrRsp) throws IOException {
    String msg = getMsg(ex);

    if (ex instanceof PartialErrors) {
      if (response != null) {
        ((SolrException) ex).encodeTypeInHttpServletResponse(response);
        response.setStatus(((SolrException) ex).code(), msg);
      }
      return true;
    } else {
      if (ex instanceof SolrException) {
        if (response != null) ((SolrException) ex).encodeTypeInHttpServletResponse(response);
        if (solrRsp != null) {
          if (((SolrException) ex).addPropertiesToParent(solrRsp.getValues())) {
            if (response != null) response.setStatus(((SolrException) ex).code(), msg);
            return true;
          }
        }
      }

      CodeAndTrace codeAndTrace = getCodeAndTrace(ex);

      if (response != null) sendError(response, codeAndTrace.code, msg + ((codeAndTrace.trace != null)?("\n\n" + codeAndTrace.trace):""));
      return false;
    }
  }

  // Would be nice to get rid of getErrorInfo and to get all use writeResponse/sendError

  /**
   * Adds the given Throwable's message to the given NamedList.
   * <p>
   * If the response code is not a regular code, the Throwable's
   * stack trace is both logged and added to the given NamedList.
   * <p>
   * Status codes less than 100 are adjusted to be 500.
   */
  public static int getErrorInfo(Throwable ex, NamedList info, Logger log) {
    String msg = getMsg(ex);
    if (msg != null) info.add("msg", msg);

    CodeAndTrace codeAndTrace = getCodeAndTrace(ex);
    info.add("code", codeAndTrace.code);
    if (codeAndTrace.trace != null) {
      info.add("trace", codeAndTrace.trace);
    }
    if (codeAndTrace.metadata != null) {
      info.add("metadata", codeAndTrace.metadata);
    }

    return codeAndTrace.code;
  }

  private static String getMsg(Throwable ex) {
    for (Throwable th = ex; th != null; th = th.getCause()) {
      String msg = th.getMessage();
      if (msg != null) {
        return msg;
      }
    }
    return null;
  }

  static class CodeAndTrace {
    int code;
    String trace;
    NamedList<String> metadata;
  }
  private static CodeAndTrace getCodeAndTrace(Throwable ex) {
    CodeAndTrace result = new CodeAndTrace();

    result.code = 500;
    if (ex instanceof SolrException) {
      SolrException solrExc = (SolrException)ex;
      result.code = solrExc.code();

      NamedList<String> errorMetadata = solrExc.getMetadata();
      if (errorMetadata == null) {
        errorMetadata = new NamedList<>();
      }
      errorMetadata.add(SolrException.ERROR_CLASS, ex.getClass().getName());
      errorMetadata.add(SolrException.ROOT_ERROR_CLASS, SolrException.getRootCause(ex).getClass().getName());
      result.metadata = errorMetadata;

      // FIXME MERGE - Solr added this, which we need to figure out where to pu (into CodeAndTrace?)
      /*
      if (ex instanceof ApiBag.ExceptionWithErrObject) {
        ApiBag.ExceptionWithErrObject exception = (ApiBag.ExceptionWithErrObject) ex;
        info.add(CommandOperation.ERR_MSGS, exception.getErrs() );
      }
     */
    }

    // For any regular code, don't include the stack trace
    if (result.code == 500 || result.code < 100) {
      StringWriter sw = new StringWriter();
      ex.printStackTrace(new PrintWriter(sw));
      SolrException.log(log, null, ex);
      result.trace = sw.toString();

      // non standard codes have undefined results with various servers
      if (result.code < 100) {
        log.warn("invalid return code: " + result.code);
        result.code = 500;
      }
    }
    
    return result;
  }

}
