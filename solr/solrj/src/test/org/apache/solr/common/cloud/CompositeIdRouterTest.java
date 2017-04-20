package org.apache.solr.common.cloud;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.solr.common.cloud.CompositeIdRouter.Positions;

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

public class CompositeIdRouterTest extends LuceneTestCase {
  private static final String ROUTE = "route";
  private static final String ID_WITHOUT_ROUTE = "idWithoutRoute";
  private static final char SEPARATOR = CompositeIdRouter.SEPARATOR;
  private static final String CHARSET = "UTF8";

  public void testSimple() throws Exception {
    String testString = ROUTE + SEPARATOR + ID_WITHOUT_ROUTE;
    BytesRef testStringAsByteRef = new BytesRef(testString);
    Positions positions = new Positions(testStringAsByteRef.offset, testStringAsByteRef.length);
    Positions routePositions = CompositeIdRouter.getRouteFromId(testString.getBytes(CHARSET), positions);
    Positions idWithoutRoutePositions = CompositeIdRouter.getIdWithoutRouteFromId(testString.getBytes(CHARSET), positions);
    
    assertEquals(ROUTE.getBytes(CHARSET).length, routePositions.length);
    assertEquals(0, routePositions.offset);
    assertEquals(ID_WITHOUT_ROUTE.getBytes(CHARSET).length, idWithoutRoutePositions.length);
    assertEquals(ROUTE.getBytes(CHARSET).length + 1, idWithoutRoutePositions.offset);
  }
  
  public void testNoRouteMarker() throws Exception {
    String testString = ROUTE + ID_WITHOUT_ROUTE;
    BytesRef testStringAsByteRef = new BytesRef(testString);
    Positions positions = new Positions(testStringAsByteRef.offset, testStringAsByteRef.length);
    Positions routePositions = CompositeIdRouter.getRouteFromId(testString.getBytes(CHARSET), positions);
    Positions idWithoutRoutePositions = CompositeIdRouter.getIdWithoutRouteFromId(testString.getBytes(CHARSET), positions);
    
    assertEquals(testString.getBytes(CHARSET).length, routePositions.length);
    assertEquals(0, routePositions.offset);
    assertNull(idWithoutRoutePositions);
  }

  public void testSeparatorAtEnd() throws Exception {
    String testString = ROUTE + SEPARATOR;
    BytesRef testStringAsByteRef = new BytesRef(testString);
    Positions positions = new Positions(testStringAsByteRef.offset, testStringAsByteRef.length);
    Positions routePositions = CompositeIdRouter.getRouteFromId(testString.getBytes(CHARSET), positions);
    Positions idWithoutRoutePositions = CompositeIdRouter.getIdWithoutRouteFromId(testString.getBytes(CHARSET), positions);
    
    assertEquals(testString.getBytes(CHARSET).length - 1, routePositions.length);
    assertEquals(0, routePositions.offset);
    assertNull(idWithoutRoutePositions);
  }

  public void testSeparatorAtStart() throws Exception {
    String testString = SEPARATOR + ID_WITHOUT_ROUTE;
    BytesRef testStringAsByteRef = new BytesRef(testString);
    Positions positions = new Positions(testStringAsByteRef.offset, testStringAsByteRef.length);
    Positions routePositions = CompositeIdRouter.getRouteFromId(testString.getBytes(CHARSET), positions);
    Positions idWithoutRoutePositions = CompositeIdRouter.getIdWithoutRouteFromId(testString.getBytes(CHARSET), positions);
    
    assertNull(routePositions);
    assertEquals(ID_WITHOUT_ROUTE.getBytes(CHARSET).length, idWithoutRoutePositions.length);
    assertEquals(1, idWithoutRoutePositions.offset);
  }

  public void testSeparatorOnly() throws Exception {
    String testString = Character.toString(SEPARATOR);
    BytesRef testStringAsByteRef = new BytesRef(testString);
    Positions positions = new Positions(testStringAsByteRef.offset, testStringAsByteRef.length);
    Positions routePositions = CompositeIdRouter.getRouteFromId(testString.getBytes(CHARSET), positions);
    Positions idWithoutRoutePositions = CompositeIdRouter.getIdWithoutRouteFromId(testString.getBytes(CHARSET), positions);
    
    assertNull(routePositions);
    assertNull(idWithoutRoutePositions);
  }

  public void testEmptyId() throws Exception {
    String testString = "";
    BytesRef testStringAsByteRef = new BytesRef(testString);
    Positions positions = new Positions(testStringAsByteRef.offset, testStringAsByteRef.length);
    Positions routePositions = CompositeIdRouter.getRouteFromId(testString.getBytes(CHARSET), positions);
    Positions idWithoutRoutePositions = CompositeIdRouter.getIdWithoutRouteFromId(testString.getBytes(CHARSET), positions);
    
    assertNull(routePositions);
    assertNull(idWithoutRoutePositions);
  }
}
