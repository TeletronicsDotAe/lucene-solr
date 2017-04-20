package org.apache.solr.common.exceptions;

import org.apache.solr.common.SolrException;

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

/**
 * ApplicationException is inspired by the JEE ApplicationException. It is the super-class for all exceptions that happens as a natural
 * part of execution in the system - exceptions that do NOT indicate problems within the system, but more likely tells "clients" that they
 * have done something wrong. One thing you typically want to do is not log ApplicationExceptions on error level
 */
public abstract class ApplicationException extends SolrException {

  private static final long serialVersionUID = 1L;
  
  public ApplicationException(ErrorCode code, String msg) {
    super(code, msg);
  }
  public ApplicationException(ErrorCode code, String msg, Throwable th) {
    super(code, msg, th);
  }

}
