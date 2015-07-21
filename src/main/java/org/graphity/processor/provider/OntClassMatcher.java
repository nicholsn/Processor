/*
 * Copyright 2014 Martynas Jusevičius <martynas@graphity.org>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.graphity.processor.provider;

import com.hp.hpl.jena.ontology.DatatypeProperty;
import com.hp.hpl.jena.ontology.OntClass;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.OntResource;
import com.hp.hpl.jena.ontology.Ontology;
import com.hp.hpl.jena.query.ParameterizedSparqlString;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.QuerySolutionMap;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;
import com.sun.jersey.api.uri.UriTemplate;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.PerRequestTypeInjectableProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import javax.servlet.ServletConfig;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Providers;
import org.graphity.core.exception.ConfigurationException;
import org.graphity.processor.vocabulary.GP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS provider for resource template class in the sitemap ontology that matches the current request.
 * 
 * @author Martynas Jusevičius <martynas@graphity.org>
 */
public class OntClassMatcher extends PerRequestTypeInjectableProvider<Context, OntClass> implements ContextResolver<OntClass>
{
    private static final Logger log = LoggerFactory.getLogger(OntClassMatcher.class);

    @Context UriInfo uriInfo;
    @Context Providers providers;
    @Context ServletConfig servletConfig;
    
    public OntClassMatcher()
    {
	super(OntClass.class);
    }

    @Override
    public Injectable<OntClass> getInjectable(ComponentContext cc, Context a)
    {
	return new Injectable<OntClass>()
	{
	    @Override
	    public OntClass getValue()
	    {
                return getOntClass();
	    }
	};
    }

    @Override
    public OntClass getContext(Class<?> type)
    {
        return getOntClass();
    }

    public OntClass getOntClass()
    {
        //try
        //{
            return matchOntClass(getServletConfig(), getOntology(), getUriInfo().getAbsolutePath(), getUriInfo().getBaseUri());
        //}
        //catch (ConfigurationException ex)
        //{
        //    throw new WebApplicationException(ex);
        //}
    }
    
    /**
     * Given an absolute URI and a base URI, returns ontology class with a matching URI template, if any.
     * 
     * @param servletConfig
     * @param ontology sitemap ontology model
     * @param uri absolute URI being matched
     * @param base base URI
     * @return matching ontology class or null, if none
     */
    public OntClass matchOntClass(ServletConfig servletConfig, Ontology ontology, URI uri, URI base) // throws ConfigurationException
    {
	if (uri == null) throw new IllegalArgumentException("URI being matched cannot be null");
	if (base == null) throw new IllegalArgumentException("Base URI cannot be null");
	if (!uri.isAbsolute()) throw new IllegalArgumentException("URI being matched \"" + uri + "\" is not absolute");
	if (base.relativize(uri).equals(uri)) throw new IllegalArgumentException("URI being matched \"" + uri + "\" is not relative to the base URI \"" + base + "\"");
	    
	StringBuilder path = new StringBuilder();
	// instead of path, include query string by relativizing request URI against base URI
	path.append("/").append(base.relativize(uri));
	return matchOntClass(servletConfig, ontology, path);
    }
    
    public Query getQuery(Query query, QuerySolutionMap qsm)
    {
        if (query == null) throw new IllegalArgumentException("Query cannot be null");
        if (qsm == null) throw new IllegalArgumentException("QuerySolution cannot be null");
        
        return new ParameterizedSparqlString(query.toString(), qsm).asQuery();
    }
    
    public Map<UriTemplate, List<OntClass>> matchOntClasses(ServletConfig servletConfig, Ontology ontology, CharSequence path) // throws ConfigurationException
    {
        QuerySolutionMap qsm = new QuerySolutionMap();
        qsm.add(RDFS.isDefinedBy.getLocalName(), ontology);

        QueryExecution qex = QueryExecutionFactory.create(getQuery(getQuery(servletConfig, GP.templatesQuery), qsm), ontology.getOntModel());
        Model templates = qex.execConstruct();
        ontology.getOntModel().add(templates); // hack?
        try
        {
            return matchOntClasses(servletConfig, templates, ontology, path);
        }
        finally
        {
            qex.close();
        }
    }
    
    /**
     * Matches path (relative URI) against URI templates in sitemap ontology.
     * This method uses Jersey implementation of the JAX-RS URI matching algorithm.
     * 
     * @param servletConfig
     * @param templates
     * @param ontology sitemap ontology
     * @param path URI path
     * @return URI template/class mapping
     */    
    public Map<UriTemplate, List<OntClass>> matchOntClasses(ServletConfig servletConfig, Model templates, Ontology ontology, CharSequence path) // throws ConfigurationException
    {
        if (templates == null) throw new IllegalArgumentException("ResultSet cannot be null");
        if (ontology == null) throw new IllegalArgumentException("Ontology cannot be null");
        if (path == null) throw new IllegalArgumentException("CharSequence cannot be null");

        if (log.isDebugEnabled()) log.debug("Matching path '{}' against resource templates in sitemap: {}", path, ontology);
        Map<UriTemplate, List<OntClass>> matchedClasses = new HashMap<>();

        ResIterator it = templates.listResourcesWithProperty(RDF.type, GP.Template);
        try
        {
            while (it.hasNext())
            {
                Resource template = it.next();
                if (!template.hasProperty(GP.uriTemplate))
                {
                    if (log.isDebugEnabled()) log.debug("gp:templatesQuery does not return {} value for {}", GP.uriTemplate, template);
                    throw new ConfigurationException("gp:templatesQuery does not return " + GP.uriTemplate + " value for " + template);
                }
                
                UriTemplate uriTemplate = new UriTemplate(template.getProperty(GP.uriTemplate).getString());
                HashMap<String, String> map = new HashMap<>();

                if (uriTemplate.match(path, map))
                {
                    OntClass ontClass = ontology.getOntModel().getOntResource(template).asClass();
                    if (log.isDebugEnabled()) log.debug("Path {} matched UriTemplate {}", path, uriTemplate);
                    if (log.isDebugEnabled()) log.debug("Path {} matched OntClass {}", path, ontClass);

                    if (!matchedClasses.containsKey(uriTemplate))
                        matchedClasses.put(uriTemplate, new ArrayList<OntClass>());
                    matchedClasses.get(uriTemplate).add(ontClass);
                }
                else
                    if (log.isTraceEnabled()) log.trace("Path {} did not match UriTemplate {}", path, uriTemplate);
            }

            if (matchedClasses.isEmpty())
            {
                ExtendedIterator<OntResource> imports = ontology.listImports();
                try
                {
                    while (imports.hasNext())
                    {
                        OntResource importRes = imports.next();
                        if (importRes.canAs(Ontology.class))
                        {
                            Ontology importedOntology = importRes.asOntology();
                            // traverse imports recursively
                            Map<UriTemplate, List<OntClass>> matchedImportClasses = matchOntClasses(servletConfig, importedOntology, path);
                            Iterator<Entry<UriTemplate, List<OntClass>>> entries = matchedImportClasses.entrySet().iterator();
                            while (entries.hasNext())
                            {
                                Entry<UriTemplate, List<OntClass>> entry = entries.next();
                                if (matchedClasses.containsKey(entry.getKey()))
                                    matchedClasses.get(entry.getKey()).addAll(entry.getValue());
                                else
                                    matchedClasses.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
                finally
                {
                    imports.close();
                }
            }
        }
        finally
        {
            it.close();
        }

        return matchedClasses;
    }
    
    /**
     * Given a relative URI and URI template property, returns ontology class with a matching URI template, if any.
     * URIs are matched against the URI templates specified in resource templates (sitemap ontology classes).
     * Templates in the base ontology model have priority (are matched first) against templates in imported ontologies.
     * 
     * @param servletConfig
     * @param ontology sitemap ontology model
     * @param path absolute path (relative URI)
     * @return matching ontology class or null, if none
d     * @see <a href="https://jsr311.java.net/nonav/releases/1.1/spec/spec3.html#x3-340003.7">3.7 Matching Requests to Resource Methods (JAX-RS 1.1)</a>
     * @see <a href="https://jersey.java.net/nonav/apidocs/1.16/jersey/com/sun/jersey/api/uri/UriTemplate.html">Jersey UriTemplate</a>
     * @see <a href="http://jena.apache.org/documentation/javadoc/jena/com/hp/hpl/jena/ontology/HasValueRestriction.html">Jena HasValueRestriction</a>
     */
    public OntClass matchOntClass(ServletConfig servletConfig, Ontology ontology, CharSequence path) // throws ConfigurationException
    {
	if (ontology == null) throw new IllegalArgumentException("OntModel cannot be null");
        
        TreeMap<UriTemplate, List<OntClass>> uriTemplateOntClassMap = new TreeMap<>(UriTemplate.COMPARATOR);

        uriTemplateOntClassMap.putAll(matchOntClasses(servletConfig, ontology, path));
        if (!uriTemplateOntClassMap.isEmpty())
        {
            List<OntClass> matchedOntClasses = uriTemplateOntClassMap.firstEntry().getValue();
            if (log.isDebugEnabled()) log.debug("Matched UriTemplate: {} OntClass: {}", uriTemplateOntClassMap.firstKey(), matchedOntClasses);
            if (matchedOntClasses.size() > 1)
                if (log.isWarnEnabled()) log.warn("URI '{}' was matched by more than one gp:Template: {}", path, matchedOntClasses);
            return matchedOntClasses.get(0);
        }
        
        if (log.isDebugEnabled()) log.debug("Path {} has no OntClass match in this OntModel", path);
        return null;
    }

    // does this belong to Skolemizer instead?
    public OntClass matchOntClass(Resource resource, OntClass parentClass)
    {
	if (resource == null) throw new IllegalArgumentException("Resource cannot be null");
        if (parentClass == null) throw new IllegalArgumentException("OntClass cannot be null");

        StmtIterator it = resource.listProperties(RDF.type);
        try
        {
            while (it.hasNext())
            {
                Statement stmt = it.next();
                if (stmt.getObject().isURIResource())
                {
                    OntClass typeClass = parentClass.getOntModel().getOntClass(stmt.getObject().asResource().getURI());
                    // return resource type which is defined by the sitemap ontology
                    if (typeClass != null && typeClass.getIsDefinedBy() != null &&
                            typeClass.getIsDefinedBy().equals(parentClass.getIsDefinedBy()))
                        return typeClass;
                }
            }
        }
        finally
        {
            it.close();
        }

        return null;
    }

    public Map<Property, List<OntClass>> ontClassesByAllValuesFrom(ServletConfig servletConfig, Ontology ontology, Property onProperty, OntClass allValuesFrom) // throws ConfigurationException
    {
	if (servletConfig == null) throw new IllegalArgumentException("ServletConfig cannot be null");        
	if (ontology == null) throw new IllegalArgumentException("OntModel cannot be null");
        //if (onProperty == null) throw new IllegalArgumentException("ObjectProperty cannot be null");
        if (allValuesFrom == null) throw new IllegalArgumentException("OntClass cannot be null");

        QuerySolutionMap qsm = new QuerySolutionMap();
        qsm.add(RDFS.isDefinedBy.getLocalName(), ontology);
        qsm.add(OWL.allValuesFrom.getLocalName(), allValuesFrom);
        if (onProperty != null) qsm.add(OWL.onProperty.getLocalName(), onProperty);

        QueryExecution qex = QueryExecutionFactory.create(getQuery(getQuery(servletConfig, GP.restrictionsQuery), qsm), ontology.getOntModel());
        try
        {
            //Map<Property, List<OntClass>> matchedClasses = ontClassesByAllValuesFrom(qex.execConstruct(), onProperty);
            Map<Property, List<OntClass>> matchedClasses = new HashMap<>();
            //Model templates = qex.execConstruct();
            //ontology.getOntModel().add(templates); // hack?
            ResultSet templates = qex.execSelect();

            //ResIterator it = templates.listResourcesWithProperty(RDF.type, GP.Template);
            while (templates.hasNext())
            {
                QuerySolution solution = templates.next();
                if (solution.contains(GP.Template.getLocalName())) // solution.contains(OWL.onProperty.getLocalName()
                {
                    OntClass template = solution.getResource(GP.Template.getLocalName()).as(OntClass.class);
                    //Property onProperty = solution.getResource(OWL.onProperty.getLocalName()).as(Property.class);

                    if (!matchedClasses.containsKey(onProperty))
                        matchedClasses.put(onProperty, new ArrayList<OntClass>());
                    matchedClasses.get(onProperty).add(template);
                }
            }

            if (matchedClasses.isEmpty())
            {
                ExtendedIterator<OntResource> imports = ontology.listImports();
                try
                {
                    while (imports.hasNext())
                    {
                        OntResource importRes = imports.next();
                        if (importRes.canAs(Ontology.class))
                        {
                            Ontology importedOntology = importRes.asOntology();
                            // traverse imports recursively
                            Map<Property, List<OntClass>> matchedImportClasses = ontClassesByAllValuesFrom(servletConfig, importedOntology, onProperty, allValuesFrom);
                            Iterator<Entry<Property, List<OntClass>>> entries = matchedImportClasses.entrySet().iterator();
                            while (entries.hasNext())
                            {
                                Entry<Property, List<OntClass>> entry = entries.next();
                                if (matchedClasses.containsKey(entry.getKey()))
                                    matchedClasses.get(entry.getKey()).addAll(entry.getValue());
                                else
                                    matchedClasses.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
                finally
                {
                    imports.close();
                }
            }

            return matchedClasses;
        }
        finally
        {
            qex.close();
        }
    }
    
    public Query getQuery(ServletConfig servletConfig, DatatypeProperty property)
    {
        if (servletConfig == null) throw new IllegalArgumentException("ServletConfig cannot be null");
        if (property == null) throw new IllegalArgumentException("Property cannot be null");
        
	Object query = servletConfig.getInitParameter(property.getURI());
        if (query == null) throw new ConfigurationException("Property '" + property.getURI() + "' needs to be set in config");
        
        ParameterizedSparqlString queryString = new ParameterizedSparqlString(query.toString());
        return queryString.asQuery();
    }
    
    public OntModel getOntModel()
    {
        return getOntology().getOntModel();
    }

    public Ontology getOntology()
    {
	ContextResolver<Ontology> cr = getProviders().getContextResolver(Ontology.class, null);
	return cr.getContext(Ontology.class);
    }

    public UriInfo getUriInfo()
    {
        return uriInfo;
    }

    public Providers getProviders()
    {
        return providers;
    }

    public ServletConfig getServletConfig()
    {
        return servletConfig;
    }
    
}