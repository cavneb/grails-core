/*
 * Copyright 2004-2005 the original author or authors.
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
package org.codehaus.groovy.grails.web.pages;

import grails.util.CollectionUtils;
import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import groovy.lang.Script;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.commons.GrailsApplication;
import org.codehaus.groovy.grails.web.errors.GrailsExceptionResolver;
import org.codehaus.groovy.grails.web.pages.exceptions.GroovyPagesException;
import org.codehaus.groovy.grails.web.pages.ext.jsp.JspTag;
import org.codehaus.groovy.grails.web.pages.ext.jsp.JspTagLib;
import org.codehaus.groovy.grails.web.pages.ext.jsp.TagLibraryResolver;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest;
import org.codehaus.groovy.grails.web.sitemesh.GSPSitemeshPage;
import org.codehaus.groovy.grails.web.sitemesh.GrailsPageFilter;
import org.codehaus.groovy.grails.web.taglib.GroovyPageAttributes;
import org.codehaus.groovy.grails.web.taglib.GroovyPageTagBody;
import org.codehaus.groovy.grails.web.taglib.GroovyPageTagWriter;
import org.codehaus.groovy.grails.web.taglib.exceptions.GrailsTagException;
import org.codehaus.groovy.grails.web.util.CodecPrintWriter;
import org.codehaus.groovy.grails.web.util.GrailsPrintWriter;
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation;

/**
 * NOTE: Based on work done by on the GSP standalone project (https://gsp.dev.java.net/)
 * <p/>
 * Base class for a GroovyPage (at the moment there is nothing in here but could be useful for
 * providing utility methods etc.
 *
 * @author Troy Heninger
 * @author Graeme Rocher
 * @author Lari Hotari
 */
public abstract class GroovyPage extends Script {

    private static final Log LOG = LogFactory.getLog(GroovyPage.class);

    public static final String REQUEST = "request";
    public static final String SERVLET_CONTEXT = "application";
    public static final String RESPONSE = "response";
    public static final String OUT = "out";
    public static final String CODEC_OUT = "codecOut";
    public static final String CODEC_OUT_STATEMENT = CODEC_OUT; // "getCodecOut()";
    public static final String OUT_STATEMENT = OUT; // "getOut()";
    public static final String CODEC_VARNAME = "Codec";
    public static final String ATTRIBUTES = "attributes";
    public static final String APPLICATION_CONTEXT = "applicationContext";
    public static final String SESSION = "session";
    public static final String PARAMS = "params";
    public static final String FLASH = "flash";
    public static final String PLUGIN_CONTEXT_PATH = "pluginContextPath";
    public static final String EXTENSION = ".gsp";
    public static final String WEB_REQUEST = "webRequest";
    public static final String DEFAULT_NAMESPACE = "g";
    public static final String LINK_NAMESPACE = "link";
    public static final String TEMPLATE_NAMESPACE = "tmpl";
    public static final String PAGE_SCOPE = "pageScope";
    public static final String CONTROLLER_NAME = "controllerName";
    public static final String SUFFIX = ".gsp";
    public static final String ACTION_NAME = "actionName";

    public static final Collection<String> RESERVED_NAMES = CollectionUtils.newSet(
            REQUEST,
            SERVLET_CONTEXT,
            RESPONSE,
            OUT,
            CODEC_OUT,
            CODEC_VARNAME,
            ATTRIBUTES,
            APPLICATION_CONTEXT,
            SESSION,
            PARAMS,
            FLASH,
            PLUGIN_CONTEXT_PATH,
            PAGE_SCOPE);

    private static final String BINDING = "binding";
    private static final String BLANK_STRING = "";
    @SuppressWarnings("rawtypes")
    private Map jspTags = Collections.EMPTY_MAP;
    private TagLibraryResolver jspTagLibraryResolver;
    private TagLibraryLookup gspTagLibraryLookup;
    private String[] htmlParts;
    private GrailsPrintWriter out;
    private GrailsPrintWriter codecOut;
    private GroovyPageOutputStack outputStack;
    private GrailsWebRequest webRequest;
    private String pluginContextPath;
    private HttpServletRequest request;

    private final List<Closure<?>> bodyClosures = new ArrayList<Closure<?>>(15);

    @SuppressWarnings("rawtypes")
    public static final class ConstantClosure extends Closure {
        private static final long serialVersionUID = 1L;
        private static final Class[] EMPTY_CLASS_ARR=new Class[0];
        final Object retval;

        public ConstantClosure(Object retval) {
            super(null);
            this.retval = retval;
        }

        @Override
        public int getMaximumNumberOfParameters() {
            return 0;
        }

        @Override
        public Class[] getParameterTypes() {
            return EMPTY_CLASS_ARR;
        }

        public Object doCall(@SuppressWarnings("unused") Object obj) {
            return retval;
        }

        public Object doCall() {
            return retval;
        }

        public Object doCall(@SuppressWarnings("unused") Object[] args) {
            return retval;
        }

        @Override
        public Object call(Object... args) {
            return retval;
        }
        
        public boolean asBoolean() {
            return DefaultTypeTransformation.castToBoolean(retval);
        }
    }

    protected static final Closure<?> EMPTY_BODY_CLOSURE = new ConstantClosure(BLANK_STRING);

    public GroovyPage() {
        init();
    }

    protected void init() {
        // do nothing
    }

    public final Writer getOut() {
        return out;
    }

    public final Writer getCodecOut() {
        return codecOut;
    }

    public void setOut(@SuppressWarnings("unused") Writer newWriter) {
        throw new IllegalStateException("Setting out in page isn't allowed.");
    }

    @SuppressWarnings("rawtypes")
    public void initRun(Writer target, GrailsWebRequest grailsWebRequest, GrailsApplication grailsApplication, Class codecClass) {
        outputStack = GroovyPageOutputStack.currentStack(true, target, false, true);
        out = outputStack.getProxyWriter();
        this.webRequest = grailsWebRequest;
        if (grailsWebRequest != null) {
            grailsWebRequest.setOut(out);
            request = grailsWebRequest.getCurrentRequest();
        }
        setVariableDirectly(OUT, out);
        if (codecClass != null) {
            codecOut = new CodecPrintWriter(grailsApplication, out, codecClass);
        } else {
            codecOut = out;
        }
        setVariableDirectly(CODEC_OUT, codecOut);
    }

    @SuppressWarnings("unchecked")
    private void setVariableDirectly(String name, Object value) {
        Binding binding = getBinding();
        if (binding instanceof AbstractGroovyPageBinding) {
            ((AbstractGroovyPageBinding)binding).setVariableDirectly(name, value);
        } else {
            binding.getVariables().put(name, value);
        }
    }

    public String getPluginContextPath() {
        return pluginContextPath != null ? pluginContextPath : BLANK_STRING;
    }

    public void setPluginContextPath(String pluginContextPath) {
        this.pluginContextPath = pluginContextPath;
    }

    public void cleanup() {
        outputStack.pop(true);
    }

    public final void createClosureForHtmlPart(int partNumber, int bodyClosureIndex) {
        final String htmlPart = htmlParts[partNumber];
        setBodyClosure(bodyClosureIndex, new ConstantClosure(htmlPart));
    }

    public final void setBodyClosure(int index, Closure<?> bodyClosure) {
        while (index >= bodyClosures.size()) {
            bodyClosures.add(null);
        }
        bodyClosures.set(index, bodyClosure);
    }

    public final Closure<?> getBodyClosure(int index) {
        if (index >= 0) {
            return bodyClosures.get(index);
        }
        return null;
    }

    /**
     * Sets the JSP tag library resolver to use to resolve JSP tags
     *
     * @param jspTagLibraryResolver The JSP tag resolve
     */
    public void setJspTagLibraryResolver(TagLibraryResolver jspTagLibraryResolver) {
        this.jspTagLibraryResolver = jspTagLibraryResolver;
    }

    /**
     * Sets the GSP tag library lookup class
     *
     * @param gspTagLibraryLookup The class used to lookup a GSP tag library
     */
    public void setGspTagLibraryLookup(TagLibraryLookup gspTagLibraryLookup) {
        this.gspTagLibraryLookup = gspTagLibraryLookup;
    }

    /**
     * Obtains a reference to the JSP tag library resolver instance
     *
     * @return The JSP TagLibraryResolver instance
     */
    TagLibraryResolver getTagLibraryResolver() {
        return jspTagLibraryResolver;
    }

    /**
     * In the development environment this method is used to evaluate expressions and improve error reporting
     *
     * @param exprText   The expression text
     * @param lineNumber The line number
     * @param outerIt    The other reference to the variable 'it'
     * @param evaluator  The expression evaluator
     * @return The result
     */
    public Object evaluate(String exprText, int lineNumber, Object outerIt, Closure<?> evaluator) {
        try {
            return evaluator.call(outerIt);
        } catch (Exception e) {
            throw new GroovyPagesException("Error evaluating expression [" + exprText + "] on line [" +
                 lineNumber + "]: " + e.getMessage(), e, lineNumber, getGroovyPageFileName());
        }
    }

    public abstract String getGroovyPageFileName();

    @Override
    public Object getProperty(String property) {
        if (OUT.equals(property)) return out;
        if (CODEC_OUT.equals(property)) return codecOut;
        // in GSP we assume if a property doesn't exist that
        // it is null rather than throw an error this works nicely
        // with the Groovy Truth
        if (BINDING.equals(property)) return getBinding();

        Object value = getBinding().getVariable(property);
        if (value != null) {
            return value;
        }

        if (value == null) {
            value = gspTagLibraryLookup != null ? gspTagLibraryLookup.lookupNamespaceDispatcher(property) : null;
            if (value == null && jspTags.containsKey(property)) {
                TagLibraryResolver tagResolver = getTagLibraryResolver();

                String uri = (String) jspTags.get(property);
                if (uri != null) {
                    value = tagResolver.resolveTagLibrary(uri);
                }
            }
            if (value != null) {
                // cache lookup for next execution
                setVariableDirectly(property, value);
            }
        }

        return value;
    }

    /* Static call for jsp tags resolution
     *
     * @param uri   tag uri
     * @param name  tag name
     * @return resolved tag if any
     */
    public JspTag getJspTag(String uri, String name){
        if (jspTagLibraryResolver == null) {
            return null;
        }

        JspTagLib tagLib = jspTagLibraryResolver.resolveTagLibrary(uri);
        return tagLib != null ? tagLib.getTag(name) : null;
    }

    /**
     * Attempts to invokes a dynamic tag
     *
     * @param tagName          The name of the tag
     * @param tagNamespace     The taglib's namespace
     * @param lineNumber       GSP source lineNumber
     * @param attrs            The tags attributes
     * @param bodyClosureIndex The index of the body variable
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public final void invokeTag(String tagName, String tagNamespace, int lineNumber, Map attrs, int bodyClosureIndex) {
        Closure body = getBodyClosure(bodyClosureIndex);

        // TODO custom namespace stuff needs to be generalized and pluggable
        if (tagNamespace.equals(TEMPLATE_NAMESPACE)) {
            final String tmpTagName = tagName;
            final Map tmpAttrs = attrs;
            tagName = "render";
            tagNamespace = DEFAULT_NAMESPACE;
            attrs = CollectionUtils.newMap("model", tmpAttrs, "template", tmpTagName);
        } else if (tagNamespace.equals(LINK_NAMESPACE)) {
            final String tmpTagName = tagName;
            final Map tmpAttrs = attrs;
            tagName = "link";
            tagNamespace = DEFAULT_NAMESPACE;
            attrs = CollectionUtils.newMap("mapping", tmpTagName);
            if (!tmpAttrs.isEmpty()) {
                attrs.put("params", tmpAttrs);
            }
        }

        try {
            GroovyObject tagLib = getTagLib(tagNamespace, tagName);
            if (tagLib != null || (gspTagLibraryLookup != null && gspTagLibraryLookup.hasNamespace(tagNamespace))) {
                if (tagLib != null) {
                    boolean returnsObject = gspTagLibraryLookup.doesTagReturnObject(tagNamespace, tagName);
                    Object tagLibProp = tagLib.getProperty(tagName);
                    if (tagLibProp instanceof Closure) {
                        Closure tag = (Closure) ((Closure) tagLibProp).clone();
                        Object tagresult = null;

                        if (!(attrs instanceof GroovyPageAttributes)) {
                            attrs = new GroovyPageAttributes(attrs);
                        }
                        switch (tag.getParameterTypes().length) {
                            case 1:
                                tagresult = tag.call(new Object[]{attrs});
                                if (returnsObject && tagresult != null && !(tagresult instanceof Writer)) {
                                    out.print(tagresult);
                                }
                                if (body != null && body != EMPTY_BODY_CLOSURE) {
                                    body.call();
                                }

                                break;

                            case 2:
                                if (tag.getParameterTypes().length == 2) {
                                    tagresult = tag.call(new Object[]{attrs, (body != null) ? body : EMPTY_BODY_CLOSURE});
                                    if (returnsObject && tagresult != null && !(tagresult instanceof Writer)) {
                                        out.print(tagresult);
                                    }
                                }
                                break;
                        }
                    } else {
                        throw new GrailsTagException("Tag [" + tagName + "] does not exist in tag library [" + tagLib.getClass().getName() + "]", getGroovyPageFileName(), lineNumber);
                    }
                } else {
                    throw new GrailsTagException("Tag [" + tagName + "] does not exist. No tag library found for namespace: " + tagNamespace, getGroovyPageFileName(), lineNumber);
                }
            } else {
                out.append('<').append(tagNamespace).append(':').append(tagName);
                for (Object o : attrs.entrySet()) {
                    Map.Entry entry = (Map.Entry) o;
                    out.append(' ');
                    out.append(entry.getKey()).append('=');
                    String value = String.valueOf(entry.getValue());
                    // handle attribute value quotes & possible escaping " -> &quot;
                    boolean containsQuotes = (value.indexOf('"') > -1);
                    boolean containsSingleQuote = (value.indexOf('\'') > -1);
                    if (containsQuotes && !containsSingleQuote) {
                        out.append('\'').append(value).append('\'');
                    } else if (containsQuotes & containsSingleQuote) {
                        out.append('\"').append(value.replaceAll("\"", "&quot;")).append('\"');
                    } else {
                        out.append('\"').append(value).append('\"');
                    }
                }
                out.append('>');
                if (body != null) {
                    Object bodyOutput = body.call();
                    if (bodyOutput != null) out.print(bodyOutput);
                }
                out.append("</").append(tagNamespace).append(':').append(tagName).append('>');
            }
        } catch (Throwable e) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Full exception for problem at " + getGroovyPageFileName() + ":" + lineNumber, e);
            }

            // The capture* tags are internal tags and not to be displayed to the user
            // hence we don't wrap the exception and simple rethrow it
            if (tagName.matches("capture(Body|Head|Meta|Title|Component)")) {
                RuntimeException rte = GrailsExceptionResolver.getFirstRuntimeException(e);
                if (rte == null) {
                    throwRootCause(tagName, tagNamespace, lineNumber, e);
                } else {
                    throw rte;
                }
            } else {
                throwRootCause(tagName, tagNamespace, lineNumber, e);
            }
        }
    }

    private void throwRootCause(String tagName, String tagNamespace, int lineNumber, Throwable e) {
        Throwable cause = GrailsExceptionResolver.getRootCause(e);
        if (cause instanceof GrailsTagException) {
            // catch and rethrow with context
            throw new GrailsTagException(cause.getMessage(), getGroovyPageFileName(), lineNumber);
        }

        throw new GrailsTagException("Error executing tag <" + tagNamespace + ":" + tagName +
                ">: " + e.getMessage(), e, getGroovyPageFileName(), lineNumber);
    }

    private GroovyObject getTagLib(String namespace, String tagName) {
        return lookupCachedTagLib(gspTagLibraryLookup, namespace, tagName);
    }

    @SuppressWarnings("rawtypes")
    public final static Object captureTagOutput(TagLibraryLookup gspTagLibraryLookup, String namespace,
                                                String tagName, Map attrs, Object body, GrailsWebRequest webRequest) {

        if (!(attrs instanceof GroovyPageAttributes)) {
            attrs = new GroovyPageAttributes(attrs);
        }
        GroovyObject tagLib = lookupCachedTagLib(gspTagLibraryLookup, namespace, tagName);

        if (tagLib == null) {
            throw new GrailsTagException("Tag [" + tagName + "] does not exist. No corresponding tag library found.");
        }

        Closure actualBody = createOutputCapturingClosure(tagLib, body, webRequest);

        final GroovyPageTagWriter out = new GroovyPageTagWriter();
        try {
            GroovyPageOutputStack outputStack = GroovyPageOutputStack.currentStack(webRequest, false);
            if (outputStack == null) {
                outputStack = GroovyPageOutputStack.currentStack(webRequest, true, out, true, true);
            }
            outputStack.push(out);
            Object tagLibProp = tagLib.getProperty(tagName); // retrieve tag lib and create wrapper writer
            if (tagLibProp instanceof Closure) {
                Closure tag = (Closure) ((Closure) tagLibProp).clone();
                Object bodyResult;

                switch (tag.getParameterTypes().length) {
                    case 1:
                        bodyResult = tag.call(new Object[]{attrs});
                        if (actualBody != null && actualBody != EMPTY_BODY_CLOSURE) {
                            Object bodyResult2 = actualBody.call();
                            if (bodyResult2 != null) {
                                out.print(bodyResult2);
                            }
                        }

                        break;
                    case 2:
                        bodyResult = tag.call(new Object[]{attrs, actualBody});
                        break;
                    default:
                        throw new GrailsTagException("Tag [" + tagName +
                                "] does not specify expected number of params in tag library [" +
                                tagLib.getClass().getName() + "]");

                }

                boolean returnsObject = gspTagLibraryLookup.doesTagReturnObject(namespace, tagName);

                if (returnsObject && bodyResult != null && !(bodyResult instanceof Writer)) {
                    return bodyResult;
                }

                // add some method to always return string, configurable?
                return out.getBuffer();
            }

            throw new GrailsTagException("Tag [" + tagName + "] does not exist in tag library [" +
                    tagLib.getClass().getName() + "]");
        } finally {
            GroovyPageOutputStack.currentStack(webRequest).pop();
        }
    }

    private final static GroovyObject lookupCachedTagLib(TagLibraryLookup gspTagLibraryLookup,
               String namespace, String tagName) {

        return gspTagLibraryLookup != null ? gspTagLibraryLookup.lookupTagLibrary(namespace, tagName) : null;
    }

    public final static Closure<?> createOutputCapturingClosure(Object wrappedInstance, final Object body1,
                                                                final GrailsWebRequest webRequest) {
        if (body1 == null) {
            return EMPTY_BODY_CLOSURE;
        }

        if (body1 instanceof GroovyPageTagBody) {
            GroovyPageTagBody gptb = (GroovyPageTagBody) body1;
            return gptb;
        }

        if (body1 instanceof ConstantClosure) {
            return (Closure<?>) body1;
        }

        if (body1 instanceof Closure) {
            return new GroovyPageTagBody(wrappedInstance, webRequest, (Closure<?>) body1);
        }

        return new ConstantClosure(body1);
    }

    /**
     * Return whether the given name cannot be used within the binding of a GSP
     *
     * @param name True if it can't
     * @return A boolean true or false
     */
    public final static boolean isReservedName(String name) {
        return RESERVED_NAMES.contains(name);
    }

    public final void printHtmlPart(final int partNumber) {
        out.write(htmlParts[partNumber]);
    }

    /**
     * Sets the JSP tags used by this GroovyPage instance
     *
     * @param jspTags The JSP tags used
     */
    @SuppressWarnings("rawtypes")
    public void setJspTags(Map jspTags) {
        this.jspTags = jspTags;
    }

    public String[] getHtmlParts() {
        return htmlParts;
    }

    public void setHtmlParts(String[] htmlParts) {
        this.htmlParts = htmlParts;
    }

    public final GroovyPageOutputStack getOutputStack() {
        return outputStack;
    }

    public final HttpServletRequest getRequest() {
        return request;
    }

    public final void registerSitemeshPreprocessMode() {
        if (request == null) {
            return;
        }

        GSPSitemeshPage page = (GSPSitemeshPage) request.getAttribute(GrailsPageFilter.GSP_SITEMESH_PAGE);
        if (page != null) {
            page.setUsed(true);
        }
    }

    public final void createTagBody(int bodyClosureIndex, Closure<?> bodyClosure) {
        GroovyPageTagBody tagBody = new GroovyPageTagBody(this, webRequest, bodyClosure, true);
        setBodyClosure(bodyClosureIndex, tagBody);
    }

    public void changeItVariable(Object value) {
        setVariableDirectly("it", value);
    }
}
