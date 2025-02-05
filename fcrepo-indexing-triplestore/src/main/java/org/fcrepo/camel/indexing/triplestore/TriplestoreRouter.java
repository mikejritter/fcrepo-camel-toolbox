/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.camel.indexing.triplestore;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.language.xpath.XPathBuilder;
import org.apache.camel.support.builder.Namespaces;
import org.fcrepo.camel.processor.EventProcessor;
import org.fcrepo.camel.processor.SparqlDeleteProcessor;
import org.fcrepo.camel.processor.SparqlUpdateProcessor;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.stream.Collectors.toList;
import static org.apache.camel.builder.PredicateBuilder.in;
import static org.apache.camel.builder.PredicateBuilder.not;
import static org.apache.camel.builder.PredicateBuilder.or;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_EVENT_TYPE;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_NAMED_GRAPH;
import static org.fcrepo.camel.FcrepoHeaders.FCREPO_URI;
import static org.fcrepo.camel.processor.ProcessorUtils.tokenizePropertyPlaceholder;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * A content router for handling Fedora events.
 *
 * @author Aaron Coburn
 */
public class TriplestoreRouter extends RouteBuilder {

    private static final Logger LOGGER = getLogger(TriplestoreRouter.class);

    private static final String RESOURCE_DELETION = "http://fedora.info/definitions/v4/event#ResourceDeletion";
    private static final String DELETE = "https://www.w3.org/ns/activitystreams#Delete";

    @Autowired
    private FcrepoTripleStoreIndexerConfig config;

    /**
     * Configure the message route workflow.
     */
    public void configure() throws Exception {

        final Namespaces ns = new Namespaces("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        ns.add("indexing", "http://fedora.info/definitions/v4/indexing#");

        final XPathBuilder indexable = new XPathBuilder(
                String.format("/rdf:RDF/rdf:Description/rdf:type[@rdf:resource='%s']",
                    "http://fedora.info/definitions/v4/indexing#Indexable"));
        indexable.namespaces(ns);

        /**
         * A generic error handler (specific to this RouteBuilder)
         */
        onException(Exception.class)
                .maximumRedeliveries(config.getMaxRedeliveries())
            .log("Index Routing Error: ${routeId}");

        /**
         * route a message to the proper queue, based on whether
         * it is a DELETE or UPDATE operation.
         */
        from(config.getInputStream())
            .routeId("FcrepoTriplestoreRouter")
            .process(new EventProcessor())
            .choice()
                .when(or(header(FCREPO_EVENT_TYPE).contains(RESOURCE_DELETION),
                            header(FCREPO_EVENT_TYPE).contains(DELETE)))
                    .log(LoggingLevel.INFO, "deleting " + header(FCREPO_URI) + " from triplestore.")
                    .to("direct:delete.triplestore")
                .otherwise()
                    .to("direct:index.triplestore");

        /**
         * Handle re-index events
         */
        from(config.getReindexStream())
            .routeId("FcrepoTriplestoreReindex")
            .to("direct:index.triplestore");

        /**
         * Based on an item's metadata, determine if it is indexable.
         */
        from("direct:index.triplestore")
                .routeId("FcrepoTriplestoreIndexer")
                .filter(not(in(tokenizePropertyPlaceholder(getContext(), config.getFilterContainers(), ",").stream()
                        .map(uri -> or(
                                header(FCREPO_URI).startsWith(constant(uri + "/")),
                                header(FCREPO_URI).isEqualTo(constant(uri))))
                        .collect(toList()))))
                .removeHeaders("CamelHttp*")
                .choice()
                .when(simple(config.isIndexingPredicate() + " != 'true'"))
                .to("direct:update.triplestore")
                .otherwise()
                .to("fcrepo:" + config.getFcrepoBaseUrl() +
                        "?preferInclude=PreferMinimalContainer&accept=application/rdf+xml")
                    .choice()
                        .when(indexable)
                            .to("direct:update.triplestore")
                        .otherwise()
                            .to("direct:delete.triplestore");

        /**
         * Remove an item from the triplestore index.
         */
        from("direct:delete.triplestore")
                .routeId("FcrepoTriplestoreDeleter")
                .process(new SparqlDeleteProcessor())
                .log(LoggingLevel.INFO, LOGGER,
                        "Deleting Triplestore Object ${headers[CamelFcrepoUri]}")
                .to(config.getTriplestoreBaseUrl() + "?useSystemProperties=true");

        /**
         * Perform the sparql update.
         */
        from("direct:update.triplestore")
                .routeId("FcrepoTriplestoreUpdater")
                .setHeader(FCREPO_NAMED_GRAPH)
                .simple(config.getNamedGraph())
                .to("fcrepo:" + config.getFcrepoBaseUrl() + "?accept=application/n-triples" +
                        "&preferOmit=" + config.getPreferOmit() + "&preferInclude=" + config.getPreferInclude())
                .process(new SparqlUpdateProcessor())
                .log(LoggingLevel.INFO, LOGGER,
                        "Indexing Triplestore Object ${headers[CamelFcrepoUri]}")
                .to(config.getTriplestoreBaseUrl() + "?useSystemProperties=true");
    }
}
