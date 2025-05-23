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

package org.apache.solr.client.solrj.embedded;

import java.util.ArrayList;
import java.util.List;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateHttp2SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.JavaBinRequestWriter;
import org.apache.solr.client.solrj.impl.JavaBinResponseParser;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

@SolrTestCaseJ4.SuppressSSL(bugUrl = "https://issues.apache.org/jira/browse/SOLR-5776")
public class SolrExampleStreamingBinaryHttp2Test extends SolrExampleStreamingHttp2Test {

  @Override
  public SolrClient createNewSolrClient() {
    String url = getBaseUrl();
    // smaller queue size hits locks more often
    Http2SolrClient solrClient =
        new Http2SolrClient.Builder()
            .withRequestWriter(new JavaBinRequestWriter())
            .withResponseParser(new JavaBinResponseParser())
            .build();
    ConcurrentUpdateHttp2SolrClient concurrentClient =
        new ErrorTrackingConcurrentUpdateSolrClient.Builder(url, solrClient)
            .withDefaultCollection(DEFAULT_TEST_CORENAME)
            .withQueueSize(2)
            .withThreadCount(5)
            .build();
    return concurrentClient;
  }

  @Test
  public void testQueryAndStreamResponse() throws Exception {
    // index a simple document with one child
    SolrClient client = getSolrClient();
    client.deleteByQuery("*:*");

    SolrInputDocument child = new SolrInputDocument();
    child.addField("id", "child");
    child.addField("type_s", "child");
    child.addField("text_s", "text");

    SolrInputDocument parent = new SolrInputDocument();
    parent.addField("id", "parent");
    parent.addField("type_s", "parent");
    parent.addChildDocument(child);

    client.add(parent);
    client.commit();

    // create a query with child doc transformer
    SolrQuery query = new SolrQuery("{!parent which='type_s:parent'}text_s:text");
    query.addField("*,[child parentFilter='type_s:parent']");

    // test regular query
    QueryResponse response = client.query(query);
    assertEquals(1, response.getResults().size());
    SolrDocument parentDoc = response.getResults().get(0);
    assertEquals(1, parentDoc.getChildDocumentCount());

    // test streaming
    final List<SolrDocument> docs = new ArrayList<>();
    client.queryAndStreamResponse(
        query,
        new StreamingResponseCallback() {
          @Override
          public void streamSolrDocument(SolrDocument doc) {
            docs.add(doc);
          }

          @Override
          public void streamDocListInfo(long numFound, long start, Float maxScore) {}
        });

    assertEquals(1, docs.size());
    parentDoc = docs.get(0);
    assertEquals(1, parentDoc.getChildDocumentCount());
  }
}
