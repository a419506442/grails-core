/*
 * Copyright 2011 the original author or authors.
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
package grails.validation;

import static org.codehaus.groovy.grails.compiler.injection.GrailsArtefactClassInjector.EMPTY_CLASS_ARRAY;
import static org.codehaus.groovy.grails.compiler.injection.GrailsArtefactClassInjector.ZERO_PARAMETERS;
import grails.util.GrailsNameUtils;
import grails.util.Holders;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.grails.compiler.injection.ASTErrorsHelper;
import org.codehaus.groovy.grails.compiler.injection.ASTValidationErrorsHelper;
import org.codehaus.groovy.grails.validation.ConstrainedProperty;
import org.codehaus.groovy.grails.validation.ConstraintsEvaluator;
import org.codehaus.groovy.grails.web.plugins.support.ValidationSupport;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class DefaultASTValidateableHelper implements ASTValidateableHelper{

    private static final String CONSTRAINED_PROPERTIES_PROPERTY_NAME = "$constraints";
    private static final String VALIDATE_METHOD_NAME = "validate";

    public void injectValidateableCode(ClassNode classNode) {
        ASTErrorsHelper errorsHelper = new ASTValidationErrorsHelper();
        errorsHelper.injectErrorsCode(classNode);
        addConstraintsField(classNode);
        addStaticInitializer(classNode);
        addGetConstraintsMethod(classNode);
        addValidateMethod(classNode);
    }

    protected void addConstraintsField(final ClassNode classNode) {
        FieldNode field = classNode.getField(CONSTRAINED_PROPERTIES_PROPERTY_NAME);
        if (field == null || !field.getDeclaringClass().equals(classNode)) {
            classNode.addField(CONSTRAINED_PROPERTIES_PROPERTY_NAME,
                Modifier.STATIC | Modifier.PRIVATE, new ClassNode(Map.class),
                new ConstantExpression(null));
        }
    }

    private void addStaticInitializer(final ClassNode classNode) {
        final Expression nullOutConstrainedPropertiesExpression = new BinaryExpression(
                new VariableExpression(CONSTRAINED_PROPERTIES_PROPERTY_NAME),
                Token.newSymbol(Types.EQUALS, 0, 0), new ConstantExpression(null));
        List<Statement> statements = new ArrayList<Statement>();
        statements.add(new ExpressionStatement(nullOutConstrainedPropertiesExpression));
        classNode.addStaticInitializerStatements(statements, true);
    }

    protected void addGetConstraintsMethod(final ClassNode classNode) {
        final String getConstraintsMethodName = "getConstraints";
        MethodNode getConstraintsMethod = classNode.getMethod(getConstraintsMethodName, ZERO_PARAMETERS);
        if (getConstraintsMethod == null || !getConstraintsMethod.getDeclaringClass().equals(classNode)) {
            final BooleanExpression isConstraintsPropertyNull = new BooleanExpression(new BinaryExpression(new VariableExpression(CONSTRAINED_PROPERTIES_PROPERTY_NAME), Token.newSymbol(
                        Types.COMPARE_EQUAL, 0, 0), new ConstantExpression(null)));

            final String servletContextHolderVariableName = "$sch";
            final String applicationContextVariableName = "$ctx";
            final String constraintsEvaluatorVariableName = "$evaluator";
            final String evaluateMethodName = "evaluate";

            final BlockStatement ifConstraintsPropertyIsNullBlockStatement = new BlockStatement();
            final Expression declareServletContextExpression = new DeclarationExpression(new VariableExpression(servletContextHolderVariableName, ClassHelper.OBJECT_TYPE), Token.newSymbol(Types.EQUALS, 0, 0), new StaticMethodCallExpression(new ClassNode(Holders.class), "getServletContext", new ArgumentListExpression()));
            final Expression declareApplicationContextExpression = new DeclarationExpression(new VariableExpression(applicationContextVariableName, ClassHelper.OBJECT_TYPE), Token.newSymbol(Types.EQUALS, 0, 0), new StaticMethodCallExpression(new ClassNode(WebApplicationContextUtils.class), "getWebApplicationContext", new ArgumentListExpression(new VariableExpression(servletContextHolderVariableName))));
            final Expression declareConstraintsEvaluatorExpression = new DeclarationExpression(new VariableExpression(constraintsEvaluatorVariableName, ClassHelper.OBJECT_TYPE), Token.newSymbol(Types.EQUALS, 0, 0), new MethodCallExpression(new VariableExpression(applicationContextVariableName), "getBean", new ArgumentListExpression(new ConstantExpression(ConstraintsEvaluator.BEAN_NAME))));
            final Expression initializeConstraintsFieldExpression = new BinaryExpression(new VariableExpression(CONSTRAINED_PROPERTIES_PROPERTY_NAME), Token.newSymbol(Types.EQUALS, 0, 0), new MethodCallExpression(new VariableExpression(constraintsEvaluatorVariableName), evaluateMethodName, new ArgumentListExpression(new VariableExpression("this"))));
            final Statement ifConstraintsPropertyIsNullStatement = new IfStatement(isConstraintsPropertyNull, ifConstraintsPropertyIsNullBlockStatement, new ExpressionStatement(new EmptyExpression()));

            ifConstraintsPropertyIsNullBlockStatement.addStatement(new ExpressionStatement(declareServletContextExpression));
            ifConstraintsPropertyIsNullBlockStatement.addStatement(new ExpressionStatement(declareApplicationContextExpression));
            ifConstraintsPropertyIsNullBlockStatement.addStatement(new ExpressionStatement(declareConstraintsEvaluatorExpression));
            ifConstraintsPropertyIsNullBlockStatement.addStatement(new ExpressionStatement(initializeConstraintsFieldExpression));
            
            final Map<String, ClassNode> propertiesToConstrain = getPropertiesToEnsureConstraintsFor(classNode);
            for (final Map.Entry<String, ClassNode> entry : propertiesToConstrain.entrySet()) {
                final String propertyName = entry.getKey();
                final ClassNode propertyType = entry.getValue();
                final String cpName = "$" + propertyName + "$constrainedProperty";
                final ArgumentListExpression constrainedPropertyConstructorArgumentList = new ArgumentListExpression();
                constrainedPropertyConstructorArgumentList.addExpression(new ClassExpression(classNode));
                constrainedPropertyConstructorArgumentList.addExpression(new ConstantExpression(propertyName));
                constrainedPropertyConstructorArgumentList.addExpression(new ClassExpression(propertyType));
                final ConstructorCallExpression constrainedPropertyCtorCallExpression = new ConstructorCallExpression(
                        new ClassNode(ConstrainedProperty.class), constrainedPropertyConstructorArgumentList);
                final Expression declareConstrainedPropertyExpression = new DeclarationExpression(
                        new VariableExpression(cpName, ClassHelper.OBJECT_TYPE),
                        Token.newSymbol(Types.EQUALS, 0, 0),
                        constrainedPropertyCtorCallExpression);
                final ArgumentListExpression applyConstraintMethodArgumentList = new ArgumentListExpression();
                applyConstraintMethodArgumentList.addExpression(new ConstantExpression(ConstrainedProperty.NULLABLE_CONSTRAINT));
                applyConstraintMethodArgumentList.addExpression(new ConstantExpression(false));
                final Expression applyNullableConstraintMethodCallExpression = new MethodCallExpression(
                        new VariableExpression(cpName), "applyConstraint", applyConstraintMethodArgumentList);
                final ArgumentListExpression putMethodArgumentList = new ArgumentListExpression();
                putMethodArgumentList.addExpression(new ConstantExpression(propertyName));
                putMethodArgumentList.addExpression(new VariableExpression(cpName));
                final MethodCallExpression addToConstraintsMapExpression = new MethodCallExpression(
                        new VariableExpression(CONSTRAINED_PROPERTIES_PROPERTY_NAME), "put", putMethodArgumentList);
                final BlockStatement addNullableConstraintBlock = new BlockStatement();
                addNullableConstraintBlock.addStatement(new ExpressionStatement(declareConstrainedPropertyExpression));
                addNullableConstraintBlock.addStatement(new ExpressionStatement(applyNullableConstraintMethodCallExpression));
                addNullableConstraintBlock.addStatement(new ExpressionStatement(addToConstraintsMapExpression));

                final Expression constraintsMapContainsKeyExpression = new MethodCallExpression(
                        new VariableExpression(CONSTRAINED_PROPERTIES_PROPERTY_NAME),
                        "containsKey", new ArgumentListExpression(new ConstantExpression(propertyName)));
                final BooleanExpression ifPropertyIsAlreadyConstrainedExpression = new BooleanExpression(constraintsMapContainsKeyExpression);
                final Statement ifPropertyIsAlreadyConstrainedStatement = new IfStatement(
                        ifPropertyIsAlreadyConstrainedExpression,
                        new ExpressionStatement(new EmptyExpression()),
                        addNullableConstraintBlock);
                ifConstraintsPropertyIsNullBlockStatement.addStatement(ifPropertyIsAlreadyConstrainedStatement);
            }

            final BlockStatement methodBlockStatement = new BlockStatement();
            methodBlockStatement.addStatement(ifConstraintsPropertyIsNullStatement);

            final Statement returnStatement = new ReturnStatement(new VariableExpression(CONSTRAINED_PROPERTIES_PROPERTY_NAME));
            methodBlockStatement.addStatement(returnStatement);

            final MethodNode methodNode = new MethodNode(getConstraintsMethodName, Modifier.STATIC | Modifier.PUBLIC, new ClassNode(Map.class), ZERO_PARAMETERS, null, methodBlockStatement);
            if (classNode.redirect() == null) {
                classNode.addMethod(methodNode);
            } else {
                classNode.redirect().addMethod(methodNode);
            }
        }
    }

    /**
     * Retrieves a Map describing all of the properties which need to be constrained for the class
     * represented by classNode.  The keys in the Map will be property names and the values are the
     * type of the corresponding property.
     * 
     * @param classNode the class to inspect
     * @return a Map describing all of the properties which need to be constrained
     */
    protected Map<String, ClassNode> getPropertiesToEnsureConstraintsFor(final ClassNode classNode) {
        final Map<String, ClassNode> fieldsToConstrain = new HashMap<String, ClassNode>();
        final List<FieldNode> allFields = classNode.getFields();
        for (final FieldNode field : allFields) {
            if (!field.isStatic()) {
                    final PropertyNode property = classNode.getProperty(field.getName());
                    if(property != null) {
                        fieldsToConstrain.put(field.getName(), field.getType());
                    }
            }
        }
        final Map<String, MethodNode> declaredMethodsMap = classNode.getDeclaredMethodsMap();
        for (Entry<String, MethodNode> methodEntry : declaredMethodsMap.entrySet()) {
            final MethodNode value = methodEntry.getValue();
            if (!value.isStatic() && value.isPublic() && classNode.equals(value.getDeclaringClass()) && value.getLineNumber() > 0) {
                Parameter[] parameters = value.getParameters();
                if (parameters != null && parameters.length == 1) {
                    final String methodName = value.getName();
                    if (methodName.startsWith("set")) {
                        final Parameter parameter = parameters[0];
                        final ClassNode paramType = parameter.getType();
                        final String restOfMethodName = methodName.substring(3);
                        final String propertyName = GrailsNameUtils.getPropertyName(restOfMethodName);
                        fieldsToConstrain.put(propertyName, paramType);
                    }
                }
            }
        }

        final ClassNode superClass = classNode.getSuperClass();
        if (!superClass.equals(new ClassNode(Object.class))) {
            fieldsToConstrain.putAll(getPropertiesToEnsureConstraintsFor(superClass));
        }
        return fieldsToConstrain;
    }

    protected void addValidateMethod(final ClassNode classNode) {
        String fieldsToValidateParameterName = "$fieldsToValidate";
        final MethodNode listArgValidateMethod = classNode.getMethod(VALIDATE_METHOD_NAME, new Parameter[]{new Parameter(new ClassNode(List.class), fieldsToValidateParameterName)});
        if (listArgValidateMethod == null) {
            final BlockStatement validateMethodCode = new BlockStatement();
            final ArgumentListExpression validateInstanceArguments = new ArgumentListExpression();
            validateInstanceArguments.addExpression(new VariableExpression("this"));
            validateInstanceArguments.addExpression(new VariableExpression(fieldsToValidateParameterName));
            final ClassNode validationSupportClassNode = new ClassNode(ValidationSupport.class);
            final StaticMethodCallExpression invokeValidateInstanceExpression = new StaticMethodCallExpression(validationSupportClassNode, "validateInstance", validateInstanceArguments);
            validateMethodCode.addStatement(new ExpressionStatement(invokeValidateInstanceExpression));
            final Parameter fieldsToValidateParameter = new Parameter(new ClassNode(List.class), fieldsToValidateParameterName);
            classNode.addMethod(new MethodNode(
                  VALIDATE_METHOD_NAME, Modifier.PUBLIC, ClassHelper.boolean_TYPE,
                  new Parameter[]{fieldsToValidateParameter}, EMPTY_CLASS_ARRAY, validateMethodCode));
        }
        final MethodNode noArgValidateMethod = classNode.getMethod(VALIDATE_METHOD_NAME,ZERO_PARAMETERS);
        if (noArgValidateMethod == null) {
            final BlockStatement validateMethodCode = new BlockStatement();

            final ArgumentListExpression validateInstanceArguments = new ArgumentListExpression();
            validateInstanceArguments.addExpression(new CastExpression(new ClassNode(List.class), new ConstantExpression(null)));
            final Expression callListArgValidateMethod = new MethodCallExpression(new VariableExpression("this"), VALIDATE_METHOD_NAME, validateInstanceArguments);
            validateMethodCode.addStatement(new ReturnStatement(callListArgValidateMethod));
            classNode.addMethod(new MethodNode(
                  VALIDATE_METHOD_NAME, Modifier.PUBLIC, ClassHelper.boolean_TYPE,
                  ZERO_PARAMETERS, EMPTY_CLASS_ARRAY, validateMethodCode));
        }
    }
}
