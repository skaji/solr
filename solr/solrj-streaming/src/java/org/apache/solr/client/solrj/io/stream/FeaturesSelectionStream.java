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

package org.apache.solr.client.solrj.io.stream;

import static org.apache.solr.client.solrj.io.stream.StreamExecutorHelper.submitAllAndAwaitAggregatingExceptions;
import static org.apache.solr.common.params.CommonParams.DISTRIB;
import static org.apache.solr.common.params.CommonParams.ID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.io.SolrClientCache;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.comp.StreamComparator;
import org.apache.solr.client.solrj.io.stream.expr.Explanation;
import org.apache.solr.client.solrj.io.stream.expr.Expressible;
import org.apache.solr.client.solrj.io.stream.expr.StreamExplanation;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionNamedParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionValue;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;

/**
 * @since 6.2.0
 */
public class FeaturesSelectionStream extends TupleStream implements Expressible {

  private static final long serialVersionUID = 1;

  protected String zkHost;
  protected String collection;
  protected Map<String, String> params;
  protected Iterator<Tuple> tupleIterator;
  protected String field;
  protected String outcome;
  protected String featureSet;
  protected int positiveLabel;
  protected int numTerms;

  protected transient SolrClientCache clientCache;
  private transient boolean doCloseCache;

  public FeaturesSelectionStream(
      String zkHost,
      String collectionName,
      Map<String, String> params,
      String field,
      String outcome,
      String featureSet,
      int positiveLabel,
      int numTerms)
      throws IOException {

    init(collectionName, zkHost, params, field, outcome, featureSet, positiveLabel, numTerms);
  }

  /** logit(collection, zkHost="", features="a,b,c,d,e,f,g", outcome="y", maxIteration="20") */
  public FeaturesSelectionStream(StreamExpression expression, StreamFactory factory)
      throws IOException {
    // grab all parameters out
    String collectionName = factory.getValueOperand(expression, 0);
    List<StreamExpressionNamedParameter> namedParams = factory.getNamedOperands(expression);
    StreamExpressionNamedParameter zkHostExpression = factory.getNamedOperand(expression, "zkHost");

    // Validate there are no unknown parameters - zkHost and alias are namedParameter, so we don't
    // need to count it twice
    if (expression.getParameters().size() != 1 + namedParams.size()) {
      throw new IOException(
          String.format(Locale.ROOT, "invalid expression %s - unknown operands found", expression));
    }

    // Collection Name
    if (null == collectionName) {
      throw new IOException(
          String.format(
              Locale.ROOT,
              "invalid expression %s - collectionName expected as first operand",
              expression));
    }

    // Named parameters - passed directly to solr as SolrParams
    if (0 == namedParams.size()) {
      throw new IOException(
          String.format(
              Locale.ROOT,
              "invalid expression %s - at least one named parameter expected. eg. 'q=*:*'",
              expression));
    }

    Map<String, String> params = new HashMap<>();
    for (StreamExpressionNamedParameter namedParam : namedParams) {
      if (!namedParam.getName().equals("zkHost")) {
        params.put(namedParam.getName(), namedParam.getParameter().toString().trim());
      }
    }

    String fieldParam = params.get("field");
    if (fieldParam != null) {
      params.remove("field");
    } else {
      throw new IOException("field param cannot be null for FeaturesSelectionStream");
    }

    String outcomeParam = params.get("outcome");
    if (outcomeParam != null) {
      params.remove("outcome");
    } else {
      throw new IOException("outcome param cannot be null for FeaturesSelectionStream");
    }

    String featureSetParam = params.get("featureSet");
    if (featureSetParam != null) {
      params.remove("featureSet");
    } else {
      throw new IOException("featureSet param cannot be null for FeaturesSelectionStream");
    }

    String positiveLabelParam = params.get("positiveLabel");
    int positiveLabel = 1;
    if (positiveLabelParam != null) {
      params.remove("positiveLabel");
      positiveLabel = Integer.parseInt(positiveLabelParam);
    }

    String numTermsParam = params.get("numTerms");
    int numTerms = 1;
    if (numTermsParam != null) {
      numTerms = Integer.parseInt(numTermsParam);
      params.remove("numTerms");
    } else {
      throw new IOException("numTerms param cannot be null for FeaturesSelectionStream");
    }

    // zkHost, optional - if not provided then will look into factory list to get
    String zkHost = null;
    if (null == zkHostExpression) {
      zkHost = factory.getCollectionZkHost(collectionName);
    } else if (zkHostExpression.getParameter() instanceof StreamExpressionValue) {
      zkHost = ((StreamExpressionValue) zkHostExpression.getParameter()).getValue();
    }
    if (null == zkHost) {
      throw new IOException(
          String.format(
              Locale.ROOT,
              "invalid expression %s - zkHost not found for collection '%s'",
              expression,
              collectionName));
    }

    // We've got all the required items
    init(
        collectionName,
        zkHost,
        params,
        fieldParam,
        outcomeParam,
        featureSetParam,
        positiveLabel,
        numTerms);
  }

  @Override
  public StreamExpressionParameter toExpression(StreamFactory factory) throws IOException {
    // functionName(collectionName, param1, param2, ..., paramN, sort="comp",
    // [aliases="field=alias,..."])

    // function name
    StreamExpression expression = new StreamExpression(factory.getFunctionName(this.getClass()));

    // collection
    expression.addParameter(collection);

    // parameters
    for (Map.Entry<String, String> param : params.entrySet()) {
      expression.addParameter(new StreamExpressionNamedParameter(param.getKey(), param.getValue()));
    }

    expression.addParameter(new StreamExpressionNamedParameter("field", field));
    expression.addParameter(new StreamExpressionNamedParameter("outcome", outcome));
    expression.addParameter(new StreamExpressionNamedParameter("featureSet", featureSet));
    expression.addParameter(
        new StreamExpressionNamedParameter("positiveLabel", String.valueOf(positiveLabel)));
    expression.addParameter(
        new StreamExpressionNamedParameter("numTerms", String.valueOf(numTerms)));

    // zkHost
    expression.addParameter(new StreamExpressionNamedParameter("zkHost", zkHost));

    return expression;
  }

  private void init(
      String collectionName,
      String zkHost,
      Map<String, String> params,
      String field,
      String outcome,
      String featureSet,
      int positiveLabel,
      int numTopTerms)
      throws IOException {
    this.zkHost = zkHost;
    this.collection = collectionName;
    this.params = params;
    this.field = field;
    this.outcome = outcome;
    this.featureSet = featureSet;
    this.positiveLabel = positiveLabel;
    this.numTerms = numTopTerms;
  }

  @Override
  public void setStreamContext(StreamContext context) {
    this.clientCache = context.getSolrClientCache();
  }

  /** Opens the CloudSolrStream */
  @Override
  public void open() throws IOException {
    if (clientCache == null) {
      doCloseCache = true;
      clientCache = new SolrClientCache();
    } else {
      doCloseCache = false;
    }
  }

  @Override
  public List<TupleStream> children() {
    return null;
  }

  private List<String> getShardUrls() throws IOException {
    try {
      var cloudSolrClient = clientCache.getCloudSolrClient(zkHost);
      Slice[] slices = CloudSolrStream.getSlices(this.collection, cloudSolrClient, false);
      Set<String> liveNodes = cloudSolrClient.getClusterState().getLiveNodes();

      List<String> baseUrls = new ArrayList<>();
      for (Slice slice : slices) {
        Collection<Replica> replicas = slice.getReplicas();
        List<Replica> shuffler = new ArrayList<>();
        for (Replica replica : replicas) {
          if (replica.getState() == Replica.State.ACTIVE
              && liveNodes.contains(replica.getNodeName())) {
            shuffler.add(replica);
          }
        }

        Collections.shuffle(shuffler, new Random());
        Replica rep = shuffler.get(0);
        String url = rep.getCoreUrl();
        baseUrls.add(url);
      }

      return baseUrls;

    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private Collection<NamedList<?>> callShards(List<String> baseUrls) throws IOException {
    List<FeaturesSelectionCall> tasks = new ArrayList<>();
    for (String baseUrl : baseUrls) {
      FeaturesSelectionCall lc =
          new FeaturesSelectionCall(
              baseUrl,
              this.params,
              this.field,
              this.outcome,
              this.positiveLabel,
              this.numTerms,
              this.clientCache);
      tasks.add(lc);
    }
    return submitAllAndAwaitAggregatingExceptions(tasks, "FeaturesSelectionStream");
  }

  @Override
  public void close() throws IOException {
    if (doCloseCache) {
      clientCache.close();
    }
  }

  /** Return the stream sort - ie, the order in which records are returned */
  @Override
  public StreamComparator getStreamSort() {
    return null;
  }

  @Override
  public Explanation toExplanation(StreamFactory factory) throws IOException {
    return new StreamExplanation(getStreamNodeId().toString())
        .withFunctionName(factory.getFunctionName(this.getClass()))
        .withImplementingClass(this.getClass().getName())
        .withExpressionType(Explanation.ExpressionType.STREAM_DECORATOR)
        .withExpression(toExpression(factory).toString());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Tuple read() throws IOException {
    try {
      if (tupleIterator == null) {
        final Map<String, Double> termScores = new HashMap<>();
        final Map<String, Long> docFreqs = new HashMap<>();

        long numDocs = 0;
        for (NamedList<?> resp : callShards(getShardUrls())) {

          @SuppressWarnings({"unchecked"})
          NamedList<Double> shardTopTerms = (NamedList<Double>) resp.get("featuredTerms");
          @SuppressWarnings({"unchecked"})
          NamedList<Integer> shardDocFreqs = (NamedList<Integer>) resp.get("docFreq");

          numDocs += (Integer) resp.get("numDocs");

          shardTopTerms.forEach(
              (term, score) -> {
                int docFreq = shardDocFreqs.get(term);
                termScores.merge(term, score, Double::sum);
                docFreqs.merge(term, (long) docFreq, Long::sum);
              });
        }
        final long numDocsF = numDocs; // make final

        final AtomicInteger idGen = new AtomicInteger(1);

        tupleIterator =
            termScores.entrySet().stream()
                .sorted( // sort by score descending
                    Comparator.<Map.Entry<String, Double>>comparingDouble(Entry::getValue)
                        .reversed())
                .limit(numTerms)
                .map(
                    (termScore) -> {
                      int index = idGen.getAndIncrement();
                      Tuple tuple = new Tuple();
                      tuple.put(ID, featureSet + "_" + index);
                      tuple.put("index_i", index);
                      tuple.put("term_s", termScore.getKey());
                      tuple.put("score_f", termScore.getValue());
                      tuple.put("featureSet_s", featureSet);
                      long docFreq = docFreqs.get(termScore.getKey());
                      double d = Math.log(((double) numDocsF / (double) (docFreq + 1)));
                      tuple.put("idf_d", d);
                      return tuple;
                    })
                .iterator();
      }
      if (tupleIterator.hasNext()) {
        return tupleIterator.next();
      } else {
        return Tuple.EOF();
      }
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  protected static class FeaturesSelectionCall implements Callable<NamedList<?>> {

    private final String baseUrl;
    private final String outcome;
    private final String field;
    private final Map<String, String> paramsMap;
    private final int positiveLabel;
    private final int numTerms;
    private final SolrClientCache clientCache;

    public FeaturesSelectionCall(
        String baseUrl,
        Map<String, String> paramsMap,
        String field,
        String outcome,
        int positiveLabel,
        int numTerms,
        SolrClientCache clientCache) {
      this.baseUrl = baseUrl;
      this.outcome = outcome;
      this.field = field;
      this.paramsMap = paramsMap;
      this.positiveLabel = positiveLabel;
      this.numTerms = numTerms;
      this.clientCache = clientCache;
    }

    @Override
    public NamedList<?> call() throws Exception {
      ModifiableSolrParams params = new ModifiableSolrParams();
      SolrClient solrClient = clientCache.getHttpSolrClient(baseUrl);

      params.add(DISTRIB, "false");
      params.add("fq", "{!igain}");

      for (Map.Entry<String, String> entry : paramsMap.entrySet()) {
        params.add(entry.getKey(), entry.getValue());
      }

      params.add("outcome", outcome);
      params.add("positiveLabel", Integer.toString(positiveLabel));
      params.add("field", field);
      params.add("numTerms", String.valueOf(numTerms));

      QueryRequest request = new QueryRequest(params);
      QueryResponse response = request.process(solrClient);
      NamedList<?> res = response.getResponse();
      return res;
    }
  }
}
