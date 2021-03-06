/*******************************************************************************
 * Copyright (c) 2018 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package com.microsoft.java.test.plugin.util;

import com.google.gson.Gson;
import com.microsoft.java.test.plugin.model.SearchTestItemParams;
import com.microsoft.java.test.plugin.model.TestItem;
import com.microsoft.java.test.plugin.model.TestLevel;
import com.microsoft.java.test.plugin.searcher.JUnit5TestSearcher;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.manipulation.CoreASTProvider;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.handlers.DocumentLifeCycleHandler;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.lsp4j.Location;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@SuppressWarnings("restriction")
public class TestSearchUtils {

    /**
     * Method to search the Code Lenses
     *
     * @param arguments contains the URI of the file to search the Code Lens.
     * @param monitor
     * @throws OperationCanceledException
     * @throws InterruptedException
     * @throws JavaModelException
     */
    public static List<TestItem> searchCodeLens(List<Object> arguments, IProgressMonitor monitor)
            throws OperationCanceledException, InterruptedException, JavaModelException {
        final List<TestItem> resultList = new LinkedList<>();
        if (arguments == null || arguments.size() == 0) {
            return resultList;
        }

        final String uri = (String) arguments.get(0);

        // wait for the LS finishing updating
        Job.getJobManager().join(DocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitor);

        final ICompilationUnit unit = JDTUtils.resolveCompilationUnit(uri);
        final IType primaryType = unit.findPrimaryType();
        if (!isJavaElementExist(unit) || primaryType == null || monitor.isCanceled()) {
            return resultList;
        }

        final CompilationUnit root = (CompilationUnit) parseToAst(unit, monitor);
        if (root == null) {
            return resultList;
        }

        final ASTNode node = root.findDeclaringNode(primaryType.getKey());
        if (!(node instanceof TypeDeclaration)) {
            return resultList;
        }

        final ITypeBinding binding = ((TypeDeclaration) node).resolveBinding();
        if (binding == null) {
            return resultList;
        }

        TestFrameworkUtils.findTestItemsInTypeBinding(binding, resultList, null /*parentClassItem*/, monitor);

        return resultList;
    }

    public static ASTNode parseToAst(final ICompilationUnit unit, IProgressMonitor monitor) {
        final CompilationUnit astRoot = CoreASTProvider.getInstance().getAST(unit, CoreASTProvider.WAIT_YES, monitor);
        if (astRoot != null) {
            return astRoot;
        }

        if (monitor.isCanceled()) {
            return null;
        }

        final ASTParser parser = ASTParser.newParser(AST.JLS14);
        parser.setSource(unit);
        parser.setFocalPosition(0);
        parser.setResolveBindings(true);
        parser.setIgnoreMethodBodies(true);
        return parser.createAST(monitor);
    }

    /**
     * Method to expand the node in Test Explorer
     *
     * @param arguments {@link com.microsoft.java.test.plugin.model.SearchTestItemParams}
     * @param monitor
     * @throws OperationCanceledException
     * @throws InterruptedException
     * @throws URISyntaxException
     * @throws JavaModelException
     */
    public static List<TestItem> searchTestItems(List<Object> arguments, IProgressMonitor monitor)
            throws OperationCanceledException, InterruptedException, URISyntaxException, JavaModelException {
        final List<TestItem> resultList = new LinkedList<>();

        if (arguments == null || arguments.size() == 0) {
            return resultList;
        }
        final Gson gson = new Gson();
        final SearchTestItemParams params = gson.fromJson((String) arguments.get(0), SearchTestItemParams.class);

        switch (params.getLevel()) {
            case FOLDER:
                searchInFolder(resultList, params);
                break;
            case PACKAGE:
                searchInPackage(resultList, params);
                break;
            case CLASS:
                searchInClass(resultList, params);
                break;
            default:
                break;
        }

        return resultList;
    }

    /**
     * Method to get all the test items when running tests from Test Explorer
     *
     * @param arguments {@link com.microsoft.java.test.plugin.model.SearchTestItemParams}
     * @param monitor
     * @throws CoreException
     * @throws InterruptedException
     * @throws URISyntaxException
     */
    public static List<TestItem> searchAllTestItems(List<Object> arguments, IProgressMonitor monitor)
            throws CoreException, InterruptedException, URISyntaxException {
        if (arguments == null || arguments.size() == 0) {
            return Collections.emptyList();
        }

        final Gson gson = new Gson();
        final SearchTestItemParams params = gson.fromJson((String) arguments.get(0), SearchTestItemParams.class);

        final IJavaSearchScope scope = createSearchScope(params);

        SearchPattern pattern = TestFrameworkUtils.FRAMEWORK_SEARCHERS[0].getSearchPattern();
        for (int i = 1; i < TestFrameworkUtils.FRAMEWORK_SEARCHERS.length; i++) {
            pattern = SearchPattern.createOrPattern(pattern,
                    TestFrameworkUtils.FRAMEWORK_SEARCHERS[i].getSearchPattern());
        }

        final Map<String, TestItem> classMap = new HashMap<>();
        final SearchRequestor requestor = new SearchRequestor() {
            @Override
            public void acceptSearchMatch(SearchMatch match) throws CoreException {
                final Object element = match.getElement();
                if (element instanceof IMethod) {
                    final IMethod method = (IMethod) element;
                    // The search result might not in the search scope.
                    // See: https://github.com/Microsoft/vscode-java-test/issues/441
                    if (!scope.encloses(method)) {
                        return;
                    }
                    final TestItem methodItem = TestFrameworkUtils.resolveTestItemForMethod(method);
                    if (methodItem == null) {
                        return;
                    }
                    final IType type = (IType) method.getParent();
                    final TestItem classItem = classMap.get(type.getFullyQualifiedName());
                    if (classItem != null) {
                        classItem.addChild(methodItem.getId());
                    } else {
                        final TestItem newClassItem = TestItemUtils.constructTestItem(type, TestLevel.CLASS,
                                methodItem.getKind());
                        newClassItem.addChild(methodItem.getId());
                        classMap.put(type.getFullyQualifiedName(), newClassItem);
                    }
                } else if (element instanceof IType) {
                    final IType type = (IType) element;
                    if (classMap.containsKey(type.getFullyQualifiedName())) {
                        return;
                    }
                    final TestItem item = TestFrameworkUtils.resolveTestItemForClass(type);
                    if (item == null) {
                        return;
                    }
                    classMap.put(type.getFullyQualifiedName(), item);
                }
            }

        };

        try {
            new SearchEngine().search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                scope, requestor, monitor);
        } catch (OperationCanceledException ex) {
            // do nothing
        }

        return new ArrayList<TestItem>(classMap.values());
    }

    public static List<Location> searchLocation(List<Object> arguments, IProgressMonitor monitor) throws CoreException {
        final List<Location> searchResult = new LinkedList<>();
        if (arguments == null || arguments.size() == 0) {
            throw new IllegalArgumentException("Invalid arguments to search the location.");
        }
        String searchString = ((String) arguments.get(0)).replaceAll("[$#]", ".");
        int searchFor = IJavaSearchConstants.METHOD;
        if (searchString.endsWith("<TestError>")) {
            searchString = searchString.substring(0, searchString.indexOf("<TestError>") - 1);
            searchFor = IJavaSearchConstants.CLASS;
        }
        final SearchPattern pattern = SearchPattern.createPattern(searchString, searchFor,
                IJavaSearchConstants.DECLARATIONS, SearchPattern.R_PATTERN_MATCH);
        final IJavaProject[] projects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot())
                .getJavaProjects();
        final IJavaSearchScope scope = SearchEngine.createJavaSearchScope(projects, IJavaSearchScope.SOURCES);
        final SearchRequestor requestor = new SearchRequestor() {
            @Override
            public void acceptSearchMatch(SearchMatch match) throws CoreException {
                final Object element = match.getElement();
                if (element instanceof IMethod || element instanceof IType) {
                    final IJavaElement javaElement = (IJavaElement) element;
                    searchResult.add(new Location(JDTUtils.getFileURI(javaElement.getResource()),
                            TestItemUtils.parseTestItemRange(javaElement)));
                }
            }
        };
        new SearchEngine().search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                scope, requestor, monitor);
        return searchResult;
    }

    private static boolean isInTestScope(IJavaElement element) throws JavaModelException {
        final IJavaProject project = element.getJavaProject();
        for (final IPath sourcePath  : ProjectUtils.listSourcePaths(project)) {
            if (!ProjectTestUtils.isTest(project, sourcePath)) {
                continue;
            }
            if (sourcePath.isPrefixOf(element.getPath())) {
                return true;
            }
        }
        return false;
    }

    private static IJavaSearchScope createSearchScope(SearchTestItemParams params)
            throws JavaModelException, URISyntaxException {
        switch (params.getLevel()) {
            case ROOT:
                final IJavaProject[] projects = Stream.of(ProjectUtils.getJavaProjects())
                        .filter(javaProject -> !ProjectsManager.DEFAULT_PROJECT_NAME
                                .equals(javaProject.getProject().getName()))
                        .toArray(IJavaProject[]::new);
                return SearchEngine.createJavaSearchScope(projects, IJavaSearchScope.SOURCES);
            case FOLDER:
                final Set<IJavaProject> projectSet = ProjectTestUtils.parseProjects(params.getUri());
                return SearchEngine.createJavaSearchScope(projectSet.toArray(new IJavaElement[projectSet.size()]),
                        IJavaSearchScope.SOURCES);
            case PACKAGE:
                final IJavaElement packageElement = resolvePackage(params.getUri(), params.getFullName());
                return SearchEngine.createJavaSearchScope(new IJavaElement[] { packageElement },
                        IJavaSearchScope.SOURCES);
            case CLASS:
                final ICompilationUnit compilationUnit = JDTUtils.resolveCompilationUnit(params.getUri());
                final IType[] types = compilationUnit.getAllTypes();
                for (final IType type : types) {
                    if (type.getFullyQualifiedName().equals(params.getFullName())) {
                        return SearchEngine.createJavaSearchScope(new IJavaElement[] { type },
                                IJavaSearchScope.SOURCES);
                    }
                }
                break;
            case METHOD:
                final String fullName = params.getFullName();
                final String className = fullName.substring(0, fullName.lastIndexOf("#"));
                final String methodName = fullName.substring(fullName.lastIndexOf("#") + 1);
                final ICompilationUnit unit = JDTUtils.resolveCompilationUnit(params.getUri());
                final IType[] allTypes = unit.getAllTypes();
                for (final IType type : allTypes) {
                    if (type.getFullyQualifiedName().equals(className)) {
                        for (final IMethod method : type.getMethods()) {
                            if (method.getElementName().equals(methodName)) {
                                return SearchEngine.createJavaSearchScope(new IJavaElement[] { method },
                                        IJavaSearchScope.SOURCES);
                            }
                        }
                    }
                }
        }

        throw new RuntimeException("Cannot resolve the search scope for " + params.getFullName());
    }

    private static void searchInFolder(List<TestItem> resultList, SearchTestItemParams params)
            throws URISyntaxException, JavaModelException {
        final Set<IJavaProject> projectSet = ProjectTestUtils.parseProjects(params.getUri());
        for (final IJavaProject project : projectSet) {
            for (final IPackageFragment packageFragment : project.getPackageFragments()) {
                if (isInTestScope(packageFragment) && packageFragment.getCompilationUnits().length > 0) {
                    resultList.add(TestItemUtils.constructTestItem(packageFragment, TestLevel.PACKAGE));
                }
            }
        }
    }

    private static void searchInPackage(List<TestItem> resultList, SearchTestItemParams params)
            throws JavaModelException {
        final IPackageFragment packageFragment = resolvePackage(params.getUri(), params.getFullName());
        if (packageFragment == null) {
            return;
        }

        for (final ICompilationUnit unit : packageFragment.getCompilationUnits()) {
            for (final IType type : unit.getTypes()) {
                resultList.add(TestItemUtils.constructTestItem(type, TestLevel.CLASS));
            }
        }
    }

    private static IPackageFragment resolvePackage(String uriString, String fullName) throws JavaModelException {
        if (TestItemUtils.DEFAULT_PACKAGE_NAME.equals(fullName)) {
            final IFolder resource = (IFolder) JDTUtils.findResource(JDTUtils.toURI(uriString),
                    ResourcesPlugin.getWorkspace().getRoot()::findContainersForLocationURI);
            final IJavaElement element = JavaCore.create(resource);
            if (element instanceof IPackageFragmentRoot) {
                final IPackageFragmentRoot packageRoot = (IPackageFragmentRoot) element;
                for (final IJavaElement child : packageRoot.getChildren()) {
                    if (child instanceof IPackageFragment && ((IPackageFragment) child).isDefaultPackage()) {
                        return (IPackageFragment) child;
                    }
                }
            }
        } else {
            return JDTUtils.resolvePackage(uriString);
        }

        return null;
    }

    private static void searchInClass(List<TestItem> resultList, SearchTestItemParams params)
            throws JavaModelException {
        final ICompilationUnit unit = JDTUtils.resolveCompilationUnit(params.getUri());
        for (final IType type : unit.getAllTypes()) {
            if (type.getFullyQualifiedName().equals(params.getFullName())) {
                for (final IType innerType : type.getTypes()) {
                    resultList.add(TestItemUtils.constructTestItem(innerType, TestLevel.CLASS));
                }

                if (!isTestableClass(type)) {
                    continue;
                }
                for (final IMethod method : type.getMethods()) {
                    final TestItem item = TestFrameworkUtils.resolveTestItemForMethod(method);
                    if (item != null) {
                        resultList.add(item);
                    }
                }
            }
        }
    }

    private static boolean isTestableClass(IType type) throws JavaModelException {
        final int flags = type.getFlags();
        if (Flags.isInterface(flags) || Flags.isAbstract(flags)) {
            return false;
        }

        final IJavaElement parent = type.getParent();

        if (parent instanceof ITypeRoot) {
            return true;
        }

        if (!(parent instanceof IType)) {
            return false;
        }

        if (isJunit5TestableClass(type)) {
            return true;
        }

        return false;
    }

    private static boolean isJunit5TestableClass(IType type) throws JavaModelException {
        final int flags = type.getFlags();

        // Classes with Testable annotation are testable
        if (TestFrameworkUtils.hasAnnotation(
                type,
                JUnit5TestSearcher.JUNIT_PLATFORM_TESTABLE,
                true /*checkHierarchy*/
        )) {
            return true;
        }

        // Jupiter's Nested annotation does not have Testable as meta annotation
        if (TestFrameworkUtils.hasAnnotation(type, JUnit5TestSearcher.JUPITER_NESTED, true /*checkHierarchy*/) ||
                (Flags.isStatic(flags) && Flags.isPublic(flags))) {
            return true;
        }

        // Classes whose inner classes are testable should also be testable
        for (final IJavaElement child : type.getChildren())  {
            if (child instanceof IType && isJunit5TestableClass((IType) child)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isJavaElementExist(IJavaElement element) {
        return element != null && element.getResource() != null && element.getResource().exists();
    }
}
