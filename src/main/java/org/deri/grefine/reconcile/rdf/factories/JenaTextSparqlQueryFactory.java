package org.deri.grefine.reconcile.rdf.factories;

import com.google.common.collect.ImmutableList;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.deri.grefine.reconcile.model.ReconciliationRequest;
import org.deri.grefine.reconcile.model.ReconciliationRequestContext.PropertyContext;
import org.deri.grefine.reconcile.model.SearchResultItem;
import org.deri.grefine.reconcile.util.StringUtils;
import org.json.JSONException;
import org.json.JSONWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * factories for queries understood by <a href="https://jena.apache.org/documentation/query/text-query.html">Jena Text</a>
 * notice that the queries use SPARQL 1.1 IN function so make sure you have a recent version of ARQ if you are using this class
 * This class is not thread-safe. meant to be used once and die, do not try to save or reuse it is cheap to create
 *
 * @author fadmaa
 */
public class JenaTextSparqlQueryFactory extends AbstractSparqlQueryFactory {

    @Override
    public String getTypeSuggestSparqlQuery(String prefix, int limit) {
        return SUGGEST_TYPE_QUERY_TEMPLATE.replace("[[QUERY]]", escapeQuery(prefix)).replaceAll("\\[\\[LIMIT\\]\\]", String.valueOf(limit));
    }

    /**
     * @param request
     * @param searchPropertyUris
     * @return sparql query according to the syntax expected by jena-text as described in their <a href="https://jena.apache.org/documentation/query/text-query.html">documentation</a>.
     * It is a standard SPARQL query apart form text:query used for full text search. <b>Note that this query uses <a href="http://www.w3.org/TR/2010/WD-sparql11-query-20101014/#func-in">IN function</a> which is only available in SPARQL 1.1</b>
     */
    @Override
    public String getReconciliationSparqlQuery(ReconciliationRequest request, ImmutableList<String> searchPropertyUris) {
        //prepare type filter
        String typesFilter = "";
        if (request.getTypes().length > 0) {
            typesFilter = StringUtils.join(request.getTypes(), ">. } UNION ", "{?entity rdf:type <", " {", ">. }}");
        }
        //prepare context filter
        StringBuilder contextFilter = new StringBuilder();
        for (PropertyContext prop : request.getContext().getProperties()) {
            contextFilter.append(PROPERTY_FILTER.replace("[[PROPERTY_URI]]", prop.getPid()).replace("[[VALUE]]", prop.getV().asSparqlValue()));
        }
        if (searchPropertyUris.size() == 1) {
            return getReconciliationSparqlQuery(SINGLE_LABEL_PROPERTY_RECONCILE_QUERY_TEMPLATE, searchPropertyUris, request.getQueryString(), typesFilter, contextFilter.toString(), "[[LABEL_PROPERTY_URI]]", searchPropertyUris.get(0), request.getLimit());
        }

        String labelFilter = buildLabelFilter(searchPropertyUris);

        return getReconciliationSparqlQuery(RECONCILE_QUERY_TEMPLATE, searchPropertyUris, request.getQueryString(), typesFilter, contextFilter.toString(), "[[LABEL_PROPERTY_FILTER]]", labelFilter, request.getLimit());
    }

    @Override
    public void write(JSONWriter writer) throws JSONException {
        writer.object();
        writer.key("type");
        writer.value("jena-text");
        writer.endObject();
    }

    private String getReconciliationSparqlQuery(String queryTemplate, ImmutableList<String> searchPropertyUris, String query, String typesFilter, String contextFilter, String labelPlaceHolder, String labelFilter, int limit) {
        String escapedQuery = escapeQuery(query);
        //the query returns a unique answer per (entity,label) pair. the *maximum* number of results is searchPropertyUris.size() * request.getLimit()
        //the answers are ordered according to their scores descendingly. thus we need to pick only the *first* request.getLimit() *unique* entity answer
        int calculatedLimit = Math.max(searchPropertyUris.size(), 1) * limit;
        return queryTemplate.replace("[[QUERY]]", escapedQuery)
                .replace(labelPlaceHolder, labelFilter)
                .replace(labelPlaceHolder, labelFilter)
                .replace("[[QUERY]]", escapedQuery)
                .replace("[[TYPE_FILTER]]", typesFilter)
                .replace("[[CONTEXT_FILTER]]", contextFilter)
                .replace("[[LIMIT]]", String.valueOf(calculatedLimit))
                .replace("[[LIMIT]]", String.valueOf(calculatedLimit));

    }

    @Override
    public String getPropertySuggestSparqlQuery(String prefix, String typeUri, int limit) {
        return SUGGEST_PROPERTY_WITH_SPECIFIC_SUBJECT_TYPE_QUERY_TEMPLATE.replaceAll("\\[\\[QUERY\\]\\]", escapeQuery(prefix)).
                replaceAll("\\[\\[LIMIT\\]\\]", String.valueOf(limit))
                .replace("[[TYPE_URI]]", typeUri);
    }

    @Override
    public String getPropertySuggestSparqlQuery(String prefix, int limit) {
        return SUGGEST_PROPERTY_QUERY_TEMPLATE.replaceAll("\\[\\[QUERY\\]\\]", prefix).replaceAll("\\[\\[LIMIT\\]\\]", String.valueOf(limit));
    }

    @Override
    public String getSampleInstancesSparqlQuery(String typeUri, ImmutableList<String> searchPropertyUris, int limit) {
        return SAMPLE_INSTANCES_OF_TYPE_QUERY_TEMPLATE.replace("[[TYPE_URI]]", typeUri)
                .replace("[[PROPERTY_URI]]", searchPropertyUris.get(0))
                .replace("[[LIMIT]]", String.valueOf(limit));
    }


    @Override
    public ImmutableList<SearchResultItem> wrapTypeSuggestResultSet(ResultSet resultSet, String prefix, int limit) {
        List<SearchResultItem> result = new ArrayList<SearchResultItem>();
        while (resultSet.hasNext()) {
            QuerySolution sol = resultSet.nextSolution();
            String pUri = sol.getResource("type").getURI();
            String label = getPreferredLabel(sol);
            result.add(new SearchResultItem(pUri, label));
        }
        return ImmutableList.copyOf(result);
    }

    @Override
    public ImmutableList<SearchResultItem> wrapPropertySuggestResultSet(ResultSet resultSet, String prefix, int limit) {
        List<SearchResultItem> result = new ArrayList<SearchResultItem>();
        while (resultSet.hasNext()) {
            QuerySolution sol = resultSet.nextSolution();
            String pUri = sol.getResource("p").getURI();
            String label = getPreferredLabel(sol);
            result.add(new SearchResultItem(pUri, label));
        }
        return ImmutableList.copyOf(result);
    }

    @Override
    public String getEntitySearchSparqlQuery(String prefix, ImmutableList<String> searchPropertyUris, int limit) {
        String labelFilter = buildLabelFilter(searchPropertyUris);

        int calculatedLimit = searchPropertyUris.size() * limit;//because we want the maximum possible number
        return SEARCH_ENTITY_QUERY_TEMPLATE.replace("[[QUERY]]", escapeQuery(prefix))
                .replace("[[LABEL_PROPERTY_FILTER]]", labelFilter)
                .replace("[[QUERY]]", escapeQuery(prefix))
                .replace("[[LIMIT]]", String.valueOf(calculatedLimit))
                .replace("[[LIMIT]]", String.valueOf(calculatedLimit));
    }

    private String getPreferredLabel(QuerySolution sol) {
        Literal s1 = sol.getLiteral("score1");
        Literal s2 = sol.getLiteral("score2");
        if (s1 != null) {
            if (s2 == null) {
                return sol.getLiteral("label1").getString();
            } else {
                if (s1.getDouble() > s2.getDouble()) {
                    return sol.getLiteral("label1").getString();
                } else {
                    return sol.getLiteral("label2").getString();
                }
            }
        } else if (s2 != null) {
            return sol.getLiteral("label2").getString();
        } else {
            return "";
        }
    }

    private String escapeQuery(String q) {
        String s = QueryParser.escape(q);
        return s.replaceAll("\\\\", "\\\\\\\\").replaceAll("'", "\\\\'");
    }

    private String buildLabelFilter(ImmutableList<String> searchPropertyUris){
        ArrayList<String> propertyTextQueries = new ArrayList<>();
        for(String propertyUri : searchPropertyUris){
            propertyTextQueries.add(
                    "{?entity text:query (<" + propertyUri + "> '[[QUERY]]*' [[LIMIT]]) . " +
                            "?entity <" + propertyUri + "> ?label . }");
        }
        return StringUtils.join(propertyTextQueries, " UNION ", "", "", "");
    }

    /**
     * A (String, double) pair
     *
     * @author fadmaa
     */
    protected static class ScoredLabel {
        final String label;
        final double score;

        public ScoredLabel(String label, double score) {
            this.label = label;
            this.score = score;
        }

        public double getScore() {
            return score;
        }

    }

    private static final String SUGGEST_TYPE_QUERY_TEMPLATE =
            "PREFIX text:<http://jena.apache.org/text#> " +
                    "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> " +
                    "PREFIX skos:<http://www.w3.org/2004/02/skos/core#> " +
                    "SELECT DISTINCT ?type ?label1 ?score1 ?label2 ?score2 " +
                    "WHERE {" +
                    "[] a ?type. " +
                    "OPTIONAL {(?type ?score1) text:query (rdfs:label '[[QUERY]]*' [[LIMIT]]) . " +
                    "?type rdfs:label ?label1 . }" +
                    "OPTIONAL {(?type ?score2) text:query (skos:prefLabel '[[QUERY]]*' [[LIMIT]]) ." +
                    "?type skos:prefLabel ?label2 . } " +
                    "FILTER (bound(?label1) || bound(?label2))" +
                    "} LIMIT [[LIMIT]]";

    private static final String SUGGEST_PROPERTY_WITH_SPECIFIC_SUBJECT_TYPE_QUERY_TEMPLATE =
            "PREFIX text:<http://jena.apache.org/text#> " +
                    "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> " +
                    "PREFIX skos:<http://www.w3.org/2004/02/skos/core#> " +
                    "SELECT DISTINCT ?p ?label1 ?score1 ?label2 ?score2 " +
                    "WHERE{" +
                    "[] a <[[TYPE_URI]]>; " +
                    "?p ?v. " +
                    "OPTIONAL {(?p ?score1) text:query (rdfs:label '[[QUERY]]*' [[LIMIT]]) . " +
                    "?p rdfs:label ?label1. }" +
                    "OPTIONAL {(?p ?score2) text:query (skos:prefLabel '[[QUERY]]*' [[LIMIT]]) . " +
                    "?p skos:prefLabel ?label2 . }" +
                    "FILTER (bound(?label1) || bound(?label2))" +
                    "} LIMIT [[LIMIT]]";

    private static final String SUGGEST_PROPERTY_QUERY_TEMPLATE =
            "PREFIX text:<http://jena.apache.org/text#> " +
                    "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> " +
                    "PREFIX skos:<http://www.w3.org/2004/02/skos/core#> " +
                    "SELECT DISTINCT ?p ?label1 ?score1 ?label2 ?score2 " +

                    "WHERE { " +
                    "[] ?p ?v. " +
                    "OPTIONAL {(?p ?score1) text:query (rdfs:label '[[QUERY]]*'  [[LIMIT]]) . " +
                    "?p rdfs:label ?label1. }" +
                    "OPTIONAL {(?p ?score2) text:query (skos:prefLabel '[[QUERY]]*' [[LIMIT]]) . " +
                    "?p skos:prefLabel ?label2. }" +
                    "FILTER (bound(?label1) || bound(?label2))" +
                    " } LIMIT [[LIMIT]]";

    private static final String RECONCILE_QUERY_TEMPLATE =
            "PREFIX text:<http://jena.apache.org/text#> " +
                    "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> " +
                    "PREFIX rdf:<http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                    "PREFIX skos:<http://www.w3.org/2004/02/skos/core#> " +
                    "SELECT ?entity ?label " +
                    "WHERE" +
                    "{" +
                    "[[LABEL_PROPERTY_FILTER]]" +
                    "[[TYPE_FILTER]]" +
                    "[[CONTEXT_FILTER]]" +
                    " FILTER (isIRI(?entity))} GROUP BY ?entity ?label " +
                    "LIMIT [[LIMIT]]";

    private static final String SINGLE_LABEL_PROPERTY_RECONCILE_QUERY_TEMPLATE =
            "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> " +
                    "PREFIX text:<http://jena.apache.org/text#> " +
                    "PREFIX rdf:<http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                    "SELECT ?entity ?label " +
                    "WHERE " +
                    "{ " +
                    "(?entity ?score1) text:query (<[[LABEL_PROPERTY_URI]]> '[[QUERY]]' [[LIMIT]]) . " +
                    "?entity <[[LABEL_PROPERTY_URI]]> ?label ." +
                    "[[TYPE_FILTER]]" +
                    "[[CONTEXT_FILTER]]" +
                    "}GROUP BY ?entity ?label " +
                    "ORDER BY DESC(?score1) LIMIT [[LIMIT]]";

    private static final String PROPERTY_FILTER = "?entity <[[PROPERTY_URI]]> [[VALUE]]. ";

    private static final String SAMPLE_INSTANCES_OF_TYPE_QUERY_TEMPLATE =
            "SELECT ?entity (SAMPLE(?label) AS ?label1) " +
                    "WHERE{" +
                    "?entity a <[[TYPE_URI]]>. " +
                    "?entity <[[PROPERTY_URI]]> ?label." +
                    "}GROUP BY ?entity LIMIT [[LIMIT]]";

    private static final String SEARCH_ENTITY_QUERY_TEMPLATE =
            "PREFIX text:<http://jena.apache.org/text#> " +
                    "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> " +
                    "PREFIX skos:<http://www.w3.org/2004/02/skos/core#> " +
                    "SELECT ?entity ?label " +
                    "WHERE {" +
                    "[[LABEL_PROPERTY_FILTER]]. " +
                    "} LIMIT [[LIMIT]]";

}
