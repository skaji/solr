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
package org.apache.solr.handler.api;

import static org.apache.solr.client.solrj.impl.JavaBinResponseParser.JAVABIN_CONTENT_TYPE_V2;
import static org.apache.solr.handler.admin.api.ReplicationAPIBase.FILE_STREAM;

import jakarta.ws.rs.core.MediaType;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.junit.Test;

public class V2ApiUtilsTest extends SolrTestCaseJ4 {

  @Test
  public void testReadsDisableV2ApiSysprop() {
    System.clearProperty("disable.v2.api");
    assertTrue("v2 API should be enabled if sysprop not specified", V2ApiUtils.isEnabled());

    System.setProperty("disable.v2.api", "false");
    assertTrue("v2 API should be enabled if sysprop explicitly enables it", V2ApiUtils.isEnabled());

    System.setProperty("disable.v2.api", "asdf");
    assertTrue("v2 API should be enabled if sysprop has unexpected value", V2ApiUtils.isEnabled());

    System.setProperty("disable.v2.api", "true");
    assertFalse(
        "v2 API should be disabled if sysprop explicitly disables it", V2ApiUtils.isEnabled());
  }

  @Test
  public void testConvertsWtToMediaTypeString() {
    assertEquals("someDefault", V2ApiUtils.getMediaTypeFromWtParam(SolrParams.of(), "someDefault"));

    var params = new ModifiableSolrParams();
    params.add("wt", "json");
    assertEquals(MediaType.APPLICATION_JSON, V2ApiUtils.getMediaTypeFromWtParam(params, null));

    params.set("wt", "xml");
    assertEquals(MediaType.APPLICATION_XML, V2ApiUtils.getMediaTypeFromWtParam(params, null));

    params.set("wt", "javabin");
    assertEquals(JAVABIN_CONTENT_TYPE_V2, V2ApiUtils.getMediaTypeFromWtParam(params, null));

    params.set("wt", FILE_STREAM);
    assertEquals(
        "application/vnd.apache.solr.raw", V2ApiUtils.getMediaTypeFromWtParam(params, null));

    // Defaults to, well the default, whenever an unknown/unexpected/typo'd 'wt' is provided
    params.set("wt", "josn");
    assertEquals("someDefault", V2ApiUtils.getMediaTypeFromWtParam(params, "someDefault"));
  }
}
