/*
 * Copyright 1999-2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.jxpath.ri;


import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;
import java.util.Map.Entry;

import org.apache.commons.jxpath.CompiledExpression;
import org.apache.commons.jxpath.Function;
import org.apache.commons.jxpath.Functions;
import org.apache.commons.jxpath.JXPathContext;
import org.apache.commons.jxpath.JXPathException;
import org.apache.commons.jxpath.PackageFunctions;
import org.apache.commons.jxpath.Pointer;
import org.apache.commons.jxpath.Variables;
import org.apache.commons.jxpath.ri.axes.InitialContext;
import org.apache.commons.jxpath.ri.axes.RootContext;
import org.apache.commons.jxpath.ri.compiler.Expression;
import org.apache.commons.jxpath.ri.compiler.LocationPath;
import org.apache.commons.jxpath.ri.compiler.Path;
import org.apache.commons.jxpath.ri.compiler.TreeCompiler;
import org.apache.commons.jxpath.ri.model.NodePointer;
import org.apache.commons.jxpath.ri.model.NodePointerFactory;
import org.apache.commons.jxpath.ri.model.VariablePointer;
import org.apache.commons.jxpath.ri.model.beans.BeanPointerFactory;
import org.apache.commons.jxpath.ri.model.beans.CollectionPointerFactory;
import org.apache.commons.jxpath.ri.model.container.ContainerPointerFactory;
import org.apache.commons.jxpath.ri.model.dynamic.DynamicPointerFactory;
import org.apache.commons.jxpath.util.TypeUtils;

/**
 * The reference implementation of JXPathContext.
 *
 * @author Dmitri Plotnikov
 * @version $Revision: 1.42 $ $Date: 2004/04/04 22:06:36 $
 */
public class JXPathContextReferenceImpl extends JXPathContext {
    
    /**
     * Change this to <code>false</code> to disable soft caching of 
     * CompiledExpressions. 
     */
    public static final boolean USE_SOFT_CACHE = true;
    
    private static final Compiler COMPILER = new TreeCompiler();
    private static Map compiled = new HashMap();
    private static final PackageFunctions GENERIC_FUNCTIONS =
        new PackageFunctions("", null);
    private static int cleanupCount = 0;
    
    private static Vector nodeFactories = new Vector();
    private static NodePointerFactory nodeFactoryArray[] = null;
    static {
        nodeFactories.add(new CollectionPointerFactory());
        nodeFactories.add(new BeanPointerFactory());
        nodeFactories.add(new DynamicPointerFactory());

        // DOM  factory is only registered if DOM support is on the classpath
        Object domFactory = allocateConditionally(
                "org.apache.commons.jxpath.ri.model.dom.DOMPointerFactory",
                "org.w3c.dom.Node");
        if (domFactory != null) {
            nodeFactories.add(domFactory);
        }

        // JDOM  factory is only registered if JDOM is on the classpath
        Object jdomFactory = allocateConditionally(
                "org.apache.commons.jxpath.ri.model.jdom.JDOMPointerFactory",
                "org.jdom.Document");
        if (jdomFactory != null) {
            nodeFactories.add(jdomFactory);
        }

        // DynaBean factory is only registered if BeanUtils are on the classpath
        Object dynaBeanFactory =
            allocateConditionally(
                "org.apache.commons.jxpath.ri.model.dynabeans."
                    + "DynaBeanPointerFactory",
                "org.apache.commons.beanutils.DynaBean");
        if (dynaBeanFactory != null) {
            nodeFactories.add(dynaBeanFactory);
        }

        nodeFactories.add(new ContainerPointerFactory());
        createNodeFactoryArray();
    }

    private Pointer rootPointer;
    private Pointer contextPointer;
    
    protected NamespaceResolver namespaceResolver;

    // The frequency of the cache cleanup
    private static final int CLEANUP_THRESHOLD = 500;

    protected JXPathContextReferenceImpl(JXPathContext parentContext,
                                         Object contextBean) 
    {
        this(parentContext, contextBean, null);
    }

    public JXPathContextReferenceImpl(
        JXPathContext parentContext,
        Object contextBean,
        Pointer contextPointer) 
    {
        super(parentContext, contextBean);

        synchronized (nodeFactories) {
            createNodeFactoryArray();
        }
                
        if (contextPointer != null) {
            this.contextPointer = contextPointer;
            this.rootPointer =
                NodePointer.newNodePointer(
                    new QName(null, "root"),
                    contextPointer.getRootNode(),
                    getLocale());
        }
        else {
            this.contextPointer =
                NodePointer.newNodePointer(
                    new QName(null, "root"),
                    contextBean,
                    getLocale());
            this.rootPointer = this.contextPointer;
        }
        
        namespaceResolver = new NamespaceResolver();
        namespaceResolver
                .setNamespaceContextPointer((NodePointer) this.contextPointer);
    }

    private static void createNodeFactoryArray() {
        if (nodeFactoryArray == null) {
            nodeFactoryArray =
                (NodePointerFactory[]) nodeFactories.
                    toArray(new NodePointerFactory[0]);
            Arrays.sort(nodeFactoryArray, new Comparator() {
                public int compare(Object a, Object b) {
                    int orderA = ((NodePointerFactory) a).getOrder();
                    int orderB = ((NodePointerFactory) b).getOrder();
                    return orderA - orderB;
                }
            });
        }
    }
    
    /**
     * Call this with a custom NodePointerFactory to add support for
     * additional types of objects.  Make sure the factory returns
     * a name that puts it in the right position on the list of factories.
     */
    public static void addNodePointerFactory(NodePointerFactory factory) {
        synchronized (nodeFactories) {
            nodeFactories.add(factory);
            nodeFactoryArray = null;
        }
    }

    public static NodePointerFactory[] getNodePointerFactories() {
        return nodeFactoryArray;
    }

    /**
     * Returns a static instance of TreeCompiler.
     * 
     * Override this to return an aternate compiler.
     */
    protected Compiler getCompiler() {
        return COMPILER;
    }
    
    protected CompiledExpression compilePath(String xpath) {
        return new JXPathCompiledExpression(xpath, compileExpression(xpath));
    }

    private Expression compileExpression(String xpath) {
        Expression expr;

        synchronized (compiled) {
            if (USE_SOFT_CACHE) {
                expr = null;
                SoftReference ref = (SoftReference) compiled.get(xpath);
                if (ref != null) {
                    expr = (Expression) ref.get();
                }
            }
            else {
                expr = (Expression) compiled.get(xpath);
            }
        }

        if (expr != null) {
            return expr;
        }

        expr = (Expression) Parser.parseExpression(xpath, getCompiler());

        synchronized (compiled) {
            if (USE_SOFT_CACHE) {
                if (cleanupCount++ >= CLEANUP_THRESHOLD) {
                    Iterator it = compiled.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry me = (Entry) it.next();
                        if (((SoftReference) me.getValue()).get() == null) {
                            it.remove();
                        }
                    }
                    cleanupCount = 0;
                }
                compiled.put(xpath, new SoftReference(expr));
            }
            else {
                compiled.put(xpath, expr);
            }
        }

        return expr;
    }

    /**
     * Traverses the xpath and returns the resulting object. Primitive
     * types are wrapped into objects.
     */
    public Object getValue(String xpath) {
        Expression expression = compileExpression(xpath);
// TODO: (work in progress) - trying to integrate with Xalan
//        Object ctxNode = getNativeContextNode(expression);
//        if (ctxNode != null) {
//            System.err.println("WILL USE XALAN: " + xpath);
//            CachedXPathAPI api = new CachedXPathAPI();
//            try {
//                if (expression instanceof Path) {
//                    Node node = api.selectSingleNode((Node)ctxNode, xpath);
//                    System.err.println("NODE: " + node);
//                    if (node == null) {
//                        return null;
//                    }
//                    return new DOMNodePointer(node, null).getValue();
//                }
//                else {
//                    XObject object = api.eval((Node)ctxNode, xpath);
//                    switch (object.getType()) {
//                    case XObject.CLASS_STRING: return object.str();
//                    case XObject.CLASS_NUMBER: return new Double(object.num());
//                    case XObject.CLASS_BOOLEAN: return new Boolean(object.bool());
//                    default:
//                        System.err.println("OTHER TYPE: " + object.getTypeString());
//                    }
//                }
//            }
//            catch (TransformerException e) {
//                // TODO Auto-generated catch block
//                e.printStackTrace();
//            }
//            return
//        }
        
        return getValue(xpath, expression);
    }

//    private Object getNativeContextNode(Expression expression) {
//        Object node = getNativeContextNode(getContextBean());
//        if (node == null) {
//            return null;
//        }
//        
//        List vars = expression.getUsedVariables();
//        if (vars != null) {
//            return null;
//        }
//        
//        return node;
//    }

//    private Object getNativeContextNode(Object bean) {
//        if (bean instanceof Number || bean instanceof String || bean instanceof Boolean) {
//            return bean;
//        }
//        if (bean instanceof Node) {
//            return (Node)bean;            
//        }
//        
//        if (bean instanceof Container) {
//            bean = ((Container)bean).getValue();
//            return getNativeContextNode(bean);
//        }
//        
//        return null;
//    }

    public Object getValue(String xpath, Expression expr) {
        Object result = expr.computeValue(getEvalContext());
        if (result == null) {
            if (expr instanceof Path) {
                if (!isLenient()) {
                    throw new JXPathException("No value for xpath: " + xpath);
                }
            }
            return null;
        }
        if (result instanceof EvalContext) {
            EvalContext ctx = (EvalContext) result;
            result = ctx.getSingleNodePointer();
            if (!isLenient() && result == null) {
                throw new JXPathException("No value for xpath: " + xpath);
            }
        }
        if (result instanceof NodePointer) {
            result = ((NodePointer) result).getValuePointer();
            if (!isLenient() && !((NodePointer) result).isActual()) {
                // We need to differentiate between pointers representing
                // a non-existing property and ones representing a property
                // whose value is null.  In the latter case, the pointer
                // is going to have isActual == false, but its parent,
                // which is a non-node pointer identifying the bean property,
                // will return isActual() == true.
                NodePointer parent = 
                    ((NodePointer) result).getImmediateParentPointer();
                if (parent == null
                    || !parent.isContainer()
                    || !parent.isActual()) {
                    throw new JXPathException("No value for xpath: " + xpath);
                }
            }
            result = ((NodePointer) result).getValue();
        }
        return result;
    }

    /**
     * Calls getValue(xpath), converts the result to the required type
     * and returns the result of the conversion.
     */
    public Object getValue(String xpath, Class requiredType) {
        Expression expr = compileExpression(xpath);
        return getValue(xpath, expr, requiredType);
    }

    public Object getValue(String xpath, Expression expr, Class requiredType) {
        Object value = getValue(xpath, expr);
        if (value != null && requiredType != null) {
            if (!TypeUtils.canConvert(value, requiredType)) {
                throw new JXPathException(
                    "Invalid expression type. '"
                        + xpath
                        + "' returns "
                        + value.getClass().getName()
                        + ". It cannot be converted to "
                        + requiredType.getName());
            }
            value = TypeUtils.convert(value, requiredType);
        }
        return value;
    }

    /**
     * Traverses the xpath and returns a Iterator of all results found
     * for the path. If the xpath matches no properties
     * in the graph, the Iterator will not be null.
     */
    public Iterator iterate(String xpath) {
        return iterate(xpath, compileExpression(xpath));
    }

    public Iterator iterate(String xpath, Expression expr) {
        return expr.iterate(getEvalContext());
    }

    public Pointer getPointer(String xpath) {
        return getPointer(xpath, compileExpression(xpath));
    }

    public Pointer getPointer(String xpath, Expression expr) {
        Object result = expr.computeValue(getEvalContext());
        if (result instanceof EvalContext) {
            result = ((EvalContext) result).getSingleNodePointer();
        }
        if (result instanceof Pointer) {
            if (!isLenient() && !((NodePointer) result).isActual()) {
                throw new JXPathException("No pointer for xpath: " + xpath);
            }
            return (Pointer) result;
        }
        else {
            return NodePointer.newNodePointer(null, result, getLocale());
        }
    }

    public void setValue(String xpath, Object value) {
        setValue(xpath, compileExpression(xpath), value);
    }


    public void setValue(String xpath, Expression expr, Object value) {
        try {
            setValue(xpath, expr, value, false);
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to set value with xpath " + xpath, ex);
        }
    }

    public Pointer createPath(String xpath) {
        return createPath(xpath, compileExpression(xpath));
    }

    public Pointer createPath(String xpath, Expression expr) {
        try {
            Object result = expr.computeValue(getEvalContext());
            Pointer pointer = null;

            if (result instanceof Pointer) {
                pointer = (Pointer) result;
            }
            else if (result instanceof EvalContext) {
                EvalContext ctx = (EvalContext) result;
                pointer = ctx.getSingleNodePointer();
            }
            else {
                checkSimplePath(expr);
                // This should never happen
                throw new JXPathException("Cannot create path:" + xpath);
            }
            return ((NodePointer) pointer).createPath(this);
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to create xpath " + xpath,
                ex);
        }
    }

    public Pointer createPathAndSetValue(String xpath, Object value) {
        return createPathAndSetValue(xpath, compileExpression(xpath), value);
    }

    public Pointer createPathAndSetValue(
        String xpath,
        Expression expr,
        Object value) 
    {
        try {
            return setValue(xpath, expr, value, true);
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to create xpath " + xpath,
                ex);
        }
    }

    private Pointer setValue(
        String xpath,
        Expression expr,
        Object value,
        boolean create) 
    {
        Object result = expr.computeValue(getEvalContext());
        Pointer pointer = null;

        if (result instanceof Pointer) {
            pointer = (Pointer) result;
        }
        else if (result instanceof EvalContext) {
            EvalContext ctx = (EvalContext) result;
            pointer = ctx.getSingleNodePointer();
        }
        else {
            if (create) {
                checkSimplePath(expr);
            }
            
            // This should never happen
            throw new JXPathException("Cannot set value for xpath: " + xpath);
        }
        if (create) {
            pointer = ((NodePointer) pointer).createPath(this, value);
        }
        else {
            pointer.setValue(value);
        }
        return pointer;
    }

    /**
     * Checks if the path follows the JXPath restrictions on the type
     * of path that can be passed to create... methods.
     */
    private void checkSimplePath(Expression expr) {
        if (!(expr instanceof LocationPath)
            || !((LocationPath) expr).isSimplePath()) {
            throw new JXPathException(
                "JXPath can only create a path if it uses exclusively "
                    + "the child:: and attribute:: axes and has "
                    + "no context-dependent predicates");
        }
    }

    /**
     * Traverses the xpath and returns an Iterator of Pointers.
     * A Pointer provides easy access to a property.
     * If the xpath matches no properties
     * in the graph, the Iterator be empty, but not null.
     */
    public Iterator iteratePointers(String xpath) {
        return iteratePointers(xpath, compileExpression(xpath));
    }

    public Iterator iteratePointers(String xpath, Expression expr) {
        return expr.iteratePointers(getEvalContext());
    }

    public void removePath(String xpath) {
        removePath(xpath, compileExpression(xpath));
    }

    public void removePath(String xpath, Expression expr) {
        try {
            NodePointer pointer = (NodePointer) getPointer(xpath, expr);
            if (pointer != null) {
                ((NodePointer) pointer).remove();
            }
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to remove xpath " + xpath,
                ex);
        }
    }

    public void removeAll(String xpath) {
        removeAll(xpath, compileExpression(xpath));
    }

    public void removeAll(String xpath, Expression expr) {
        try {
            ArrayList list = new ArrayList();
            Iterator it = expr.iteratePointers(getEvalContext());
            while (it.hasNext()) {
                list.add(it.next());
            }
            Collections.sort(list);
            for (int i = list.size() - 1; i >= 0; i--) {
                NodePointer pointer = (NodePointer) list.get(i);
                pointer.remove();
            }
        }
        catch (Throwable ex) {
            throw new JXPathException(
                "Exception trying to remove all for xpath " + xpath,
                ex);
        }
    }

    public JXPathContext getRelativeContext(Pointer pointer) {
        Object contextBean = pointer.getNode();
        if (contextBean == null) {
            throw new JXPathException(
                "Cannot create a relative context for a non-existent node: "
                    + pointer);
        }
        return new JXPathContextReferenceImpl(this, contextBean, pointer);
    }
    
    public Pointer getContextPointer() {
        return contextPointer;
    }

    private NodePointer getAbsoluteRootPointer() {
        return (NodePointer) rootPointer;
    }

    private EvalContext getEvalContext() {
        return new InitialContext(new RootContext(this,
                (NodePointer) getContextPointer()));
    }

    public EvalContext getAbsoluteRootContext() {
        return new InitialContext(new RootContext(this,
                getAbsoluteRootPointer()));
    }

    public NodePointer getVariablePointer(QName name) {
        String varName = name.toString();
        JXPathContext varCtx = this;
        Variables vars = null;
        while (varCtx != null) {
            vars = varCtx.getVariables();
            if (vars.isDeclaredVariable(varName)) {
                break;
            }
            varCtx = varCtx.getParentContext();
            vars = null;
        }
        if (vars != null) {
            return new VariablePointer(vars, name);
        }
        else {
            // The variable is not declared, but we will create
            // a pointer anyway in case the user want to set, rather
            // than get, the value of the variable.
            return new VariablePointer(name);
        }
    }

    public Function getFunction(QName functionName, Object[] parameters) {
        String namespace = functionName.getPrefix();
        String name = functionName.getName();
        JXPathContext funcCtx = this;
        Function func = null;
        Functions funcs;
        while (funcCtx != null) {
            funcs = funcCtx.getFunctions();
            if (funcs != null) {
                func = funcs.getFunction(namespace, name, parameters);
                if (func != null) {
                    return func;
                }
            }
            funcCtx = funcCtx.getParentContext();
        }
        func = GENERIC_FUNCTIONS.getFunction(namespace, name, parameters);
        if (func != null) {
            return func;
        }
        throw new JXPathException(
            "Undefined function: " + functionName.toString());
    }
    
    public void registerNamespace(String prefix, String namespaceURI) {
        if (namespaceResolver.isSealed()) {            
            namespaceResolver = (NamespaceResolver) namespaceResolver.clone();
        }
        namespaceResolver.registerNamespace(prefix, namespaceURI);
    }
    
    public String getNamespaceURI(String prefix) {
        return namespaceResolver.getNamespaceURI(prefix);
    }
    
    public void setNamespaceContextPointer(Pointer pointer) {
        if (namespaceResolver.isSealed()) {
            namespaceResolver = (NamespaceResolver) namespaceResolver.clone();
        }
        namespaceResolver.setNamespaceContextPointer((NodePointer) pointer);
    }
    
    public Pointer getNamespaceContextPointer() {
        return namespaceResolver.getNamespaceContextPointer();
    }

    public NamespaceResolver getNamespaceResolver() {
        namespaceResolver.seal();
        return namespaceResolver;
    }
    
    /**
     * Checks if existenceCheckClass exists on the class path. If so, allocates
     * an instance of the specified class, otherwise returns null.
     */
    public static Object allocateConditionally(
            String className,
            String existenceCheckClassName) 
    {
        try {
            try {
                Class.forName(existenceCheckClassName);
            }
            catch (ClassNotFoundException ex) {
                return null;
            }

            Class cls = Class.forName(className);
            return cls.newInstance();
        }
        catch (Exception ex) {
            throw new JXPathException("Cannot allocate " + className, ex);
        }
    }
}