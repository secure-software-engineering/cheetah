/*******************************************************************************
 * Copyright (c) 2012 Secure Software Engineering Group at EC SPRIDE.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package layeredtaintplugin.android;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import layeredtaintplugin.Config;
import soot.ArrayType;
import soot.Body;
import soot.IntType;
import soot.Local;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.VoidType;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.IfStmt;
import soot.jimple.IntConstant;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NopStmt;
import soot.jimple.Stmt;
import soot.jimple.infoflow.data.SootMethodAndClass;
import soot.jimple.infoflow.entryPointCreators.BaseEntryPointCreator;
import soot.jimple.infoflow.util.SootMethodRepresentationParser;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JEqExpr;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JNopStmt;
import soot.jimple.toolkits.scalar.NopEliminator;
import soot.options.Options;

public class AndroidEntryPointCreatorJIT extends BaseEntryPointCreator {

	private static final boolean DEBUG = false;

	private LocalGenerator generator;
	private int conditionCounter;
	private Value intCounter;

	private SootClass applicationClass = null;
	private Local applicationLocal = null;
	private final Set<SootClass> applicationCallbackClasses = new HashSet<SootClass>();

	private final Collection<String> androidClasses;
	private final Collection<String> additionalEntryPoints;

	private Map<String, List<String>> callbackFunctions;
	private final boolean modelAdditionalMethods = false;

	private final Map<SootClass, ComponentType> componentTypeCache = new HashMap<SootClass, ComponentType>();

	/**
	 * Array containing all types of components supported in Android lifecycles
	 */
	private enum ComponentType {
		Application, Activity, Service, BroadcastReceiver, ContentProvider, Plain
	}

	/**
	 * Creates a new instance of the {@link AndroidEntryPointCreatorJIT} class
	 * and registers a list of classes to be automatically scanned for Android
	 * lifecycle methods
	 * 
	 * @param androidClasses
	 *            The list of classes to be automatically scanned for Android
	 *            lifecycle methods
	 */
	public AndroidEntryPointCreatorJIT(Collection<String> androidClasses) {
		this(androidClasses, null);
		this.setDummyClassName(Config.dummyMainClassName);
		this.setDummyMethodName(Config.dummyMainMethodName);
	}

	public AndroidEntryPointCreatorJIT(Collection<String> androidClasses, Collection<String> additionalEntryPoints) {
		this.androidClasses = androidClasses;
		if (additionalEntryPoints == null)
			this.additionalEntryPoints = Collections.emptySet();
		else
			this.additionalEntryPoints = additionalEntryPoints;
		this.callbackFunctions = new HashMap<String, List<String>>();

		setDummyClassName(Config.dummyMainClassName);
		setDummyMethodName(Config.dummyMainMethodName);
	}

	public void setCallbackFunctions(Map<String, List<String>> callbackFunctions) {
		this.callbackFunctions = callbackFunctions;
	}

	@Override
	public Collection<String> getRequiredClasses() {
		Set<String> requiredClasses = new HashSet<String>(androidClasses);
		requiredClasses
				.addAll(SootMethodRepresentationParser.v().parseClassNames(additionalEntryPoints, false).keySet());
		return requiredClasses;
	}

	public SootMethod createDummyMainForClass(String className) {
		SootMethod emptySootMethod = createEmptyMainMethod(Jimple.v().newBody(), className);
		createDummyMainInternal(emptySootMethod, className);
		return emptySootMethod;
	}

	public SootMethod createFullDummyMain() {
		SootMethod emptySootMethod = createEmptyMainMethod(Jimple.v().newBody());
		createDummyMainFull(emptySootMethod);
		return emptySootMethod;
	}

	protected SootMethod createEmptyMainMethod(Body body, String className) {
		Scene.v().forceResolve(className, SootClass.SIGNATURES);

		final SootClass mainClass;

		String methodName = Config.dummyMainMethodName;
		if (Scene.v().containsClass(className)) {
			int methodIndex = 0;
			mainClass = Scene.v().getSootClass(className);
			while (mainClass.declaresMethodByName(methodName))
				methodName = Config.dummyMainMethodName + "_" + methodIndex++;
		} else
			throw new RuntimeException("SootClass does not exist " + className);

		Type stringArrayType = ArrayType.v(RefType.v("java.lang.String"), 1);
		SootMethod mainMethod = new SootMethod(methodName, Collections.singletonList(stringArrayType), VoidType.v());
		body.setMethod(mainMethod);
		mainMethod.setActiveBody(body);
		mainClass.addMethod(mainMethod);

		// Add a parameter reference to the body
		LocalGenerator lg = new LocalGenerator(body);
		Local paramLocal = lg.generateLocal(stringArrayType);
		body.getUnits()
				.addFirst(Jimple.v().newIdentityStmt(paramLocal, Jimple.v().newParameterRef(stringArrayType, 0)));

		// First add class to scene, then make it an application class
		// as addClass contains a call to "setLibraryClass"
		mainClass.setApplicationClass();
		mainMethod.setModifiers(Modifier.PUBLIC);
		return mainMethod;
	}

	private SootMethod createDummyMainFull(SootMethod emptySootMethod) {

		Map<String, Set<String>> classMap = SootMethodRepresentationParser.v().parseClassNames(additionalEntryPoints,
				false);

		for (String androidClass : this.androidClasses)
			if (!classMap.containsKey(androidClass))
				classMap.put(androidClass, new HashSet<String>());

		JimpleBody body = (JimpleBody) emptySootMethod.getActiveBody();
		generator = new LocalGenerator(body);

		// add entrypoint calls
		conditionCounter = 0;
		intCounter = generator.generateLocal(IntType.v());

		JAssignStmt assignStmt = new JAssignStmt(intCounter, IntConstant.v(conditionCounter));
		body.getUnits().add(assignStmt);

		// No need to resolve classes, previous layers did already
		// for (String className : classMap.keySet())
		// Scene.v().forceResolve(className, SootClass.SIGNATURES);

		// For some weird reason unknown to anyone except the flying spaghetti
		// monster, the onCreate() methods of content providers run even before
		// the application object's onCreate() is called.
		{
			boolean hasContentProviders = false;
			JNopStmt beforeContentProvidersStmt = new JNopStmt();
			body.getUnits().add(beforeContentProvidersStmt);
			for (String className : classMap.keySet()) {
				SootClass currentClass = Scene.v().getSootClass(className);
				if (getComponentType(currentClass) == ComponentType.ContentProvider) {
					// Create an instance of the content provider
					Local localVal = generateClassConstructor(currentClass, body);
					if (localVal == null) {
						logger.warn("Constructor cannot be generated for {}", currentClass.getName());
						continue;
					}
					localVarsForClasses.put(currentClass.getName(), localVal);

					// Conditionally call the onCreate method
					JNopStmt thenStmt = new JNopStmt();
					createIfStmt(thenStmt, body);
					buildMethodCall(findMethod(currentClass, AndroidEntryPointConstants.CONTENTPROVIDER_ONCREATE), body,
							localVal, generator);
					body.getUnits().add(thenStmt);
					hasContentProviders = true;
				}
			}
			// Jump back to the beginning of this section to overapproximate the
			// order in which the methods are called
			if (hasContentProviders)
				createIfStmt(beforeContentProvidersStmt, body);
		}

		// If we have an application, we need to start it in the very beginning
		for (Entry<String, Set<String>> entry : classMap.entrySet()) {
			SootClass currentClass = Scene.v().getSootClass(entry.getKey());
			List<SootClass> extendedClasses = Scene.v().getActiveHierarchy().getSuperclassesOf(currentClass);
			for (SootClass sc : extendedClasses)
				if (sc.getName().equals(AndroidEntryPointConstants.APPLICATIONCLASS)) {
					if (applicationClass != null)
						throw new RuntimeException("Multiple application classes in app");
					applicationClass = currentClass;
					applicationCallbackClasses.add(applicationClass);

					// Create the application
					applicationLocal = generateClassConstructor(applicationClass, body);
					if (applicationLocal == null) {
						logger.warn("Constructor cannot be generated for application class {}",
								applicationClass.getName());
						continue;
					}
					localVarsForClasses.put(applicationClass.getName(), applicationLocal);

					// Create instances of all application callback classes
					if (callbackFunctions.containsKey(applicationClass.getName())) {
						NopStmt beforeCbCons = new JNopStmt();
						body.getUnits().add(beforeCbCons);
						for (String appCallback : callbackFunctions.get(applicationClass.getName())) {
							JNopStmt thenStmt = new JNopStmt();
							createIfStmt(thenStmt, body);

							String callbackClass = SootMethodRepresentationParser.v().parseSootMethodString(appCallback)
									.getClassName();
							Local l = localVarsForClasses.get(callbackClass);
							if (l == null) {
								SootClass theClass = Scene.v().getSootClass(callbackClass);
								applicationCallbackClasses.add(theClass);
								l = generateClassConstructor(theClass, body, Collections.singleton(applicationClass));
								if (l != null)
									localVarsForClasses.put(callbackClass, l);
							}

							body.getUnits().add(thenStmt);
						}
						// Jump back to overapproximate the order in which the
						// constructors are called
						createIfStmt(beforeCbCons, body);
					}

					// Call the onCreate() method
					searchAndBuildMethod(AndroidEntryPointConstants.APPLICATION_ONCREATE, applicationClass,
							entry.getValue(), applicationLocal, body);
					break;
				}
		}

		// prepare outer loop:
		JNopStmt outerStartStmt = new JNopStmt();
		body.getUnits().add(outerStartStmt);

		for (Entry<String, Set<String>> entry : classMap.entrySet()) {
			SootClass currentClass = Scene.v().getSootClass(entry.getKey());
			ComponentType componentType = getComponentType(currentClass);
			if (componentType != ComponentType.Application
					&& currentClass.declaresMethodByName(Config.dummyMainMethodName)) {

				JNopStmt entryExitStmt = new JNopStmt();
				createIfStmt(entryExitStmt, body);

				JNopStmt endClassStmt = new JNopStmt();

				Local localVal = generateClassConstructor(currentClass, body);
				if (localVal != null) {
					localVarsForClasses.put(currentClass.getName(), localVal);
					Local classLocal = localVarsForClasses.get(currentClass.getName());

					// Get call to the dummyMain of currentClass
					SootMethod currentDM = currentClass.getMethodByName(Config.dummyMainMethodName);
					buildMethodCall(currentDM, body, classLocal, generator);
				}

				body.getUnits().add(endClassStmt);
				body.getUnits().add(entryExitStmt);
			}
		}

		// Add conditional calls to the application callback methods
		if (applicationLocal != null) {
			Unit beforeAppCallbacks = Jimple.v().newNopStmt();
			body.getUnits().add(beforeAppCallbacks);
			addApplicationCallbackMethods(body);
			createIfStmt(beforeAppCallbacks, body);
		}

		createIfStmt(outerStartStmt, body);

		// Add a call to application.onTerminate()
		if (applicationLocal != null)
			searchAndBuildMethod(AndroidEntryPointConstants.APPLICATION_ONTERMINATE, applicationClass,
					classMap.get(applicationClass.getName()), applicationLocal, body);

		body.getUnits().add(Jimple.v().newReturnVoidStmt());

		// Optimize and check the generated main method
		NopEliminator.v().transform(body);
		eliminateSelfLoops(body);
		eliminateFallthroughIfs(body);

		if (DEBUG || Options.v().validate())
			emptySootMethod.getActiveBody().validate();

		return emptySootMethod;
	}

	protected SootMethod createDummyMainInternal(SootMethod emptySootMethod, String forClass) {

		JimpleBody body = (JimpleBody) emptySootMethod.getActiveBody();
		generator = new LocalGenerator(body);

		// add entrypoint calls
		conditionCounter = 0;
		intCounter = generator.generateLocal(IntType.v());

		// Resolve all requested classes
		SootClass currentClass = Scene.v().forceResolve(forClass, SootClass.SIGNATURES);
		currentClass.setApplicationClass();

		Set<String> classSet = new HashSet<String>();
		JNopStmt endClassStmt = new JNopStmt();

		JAssignStmt assignStmt = new JAssignStmt(intCounter, IntConstant.v(conditionCounter));
		body.getUnits().add(assignStmt);

		try {
			ComponentType componentType = getComponentType(currentClass);

			// Check if one of the methods is instance. This tells us
			// whether
			// we need to create a constructor invocation or not.
			// Furthermore,
			// we collect references to the corresponding SootMethod
			// objects.
			boolean instanceNeeded = componentType == ComponentType.Activity || componentType == ComponentType.Service
					|| componentType == ComponentType.BroadcastReceiver
					|| componentType == ComponentType.ContentProvider;
			Map<String, SootMethod> plainMethods = new HashMap<String, SootMethod>();
			if (!instanceNeeded)
				for (String method : classSet) {
					SootMethod sm = null;

					// Find the method. It may either be implemented
					// directly in the
					// given class or it may be inherited from one of the
					// superclasses.
					if (Scene.v().containsMethod(method))
						sm = Scene.v().getMethod(method);
					else {
						SootMethodAndClass methodAndClass = SootMethodRepresentationParser.v()
								.parseSootMethodString(method);
						if (!Scene.v().containsClass(methodAndClass.getClassName())) {
							logger.warn("Class for entry point {} not found, skipping...", method);
							continue;
						}
						sm = findMethod(Scene.v().getSootClass(methodAndClass.getClassName()),
								methodAndClass.getSubSignature());
						if (sm == null) {
							logger.warn("Method for entry point {} not found in class, skipping...", method);
							continue;
						}
					}

					plainMethods.put(method, sm);
					if (!sm.isStatic())
						instanceNeeded = true;
				}

			// if we need to call a constructor, we insert the respective
			// Jimple statement here
			if (instanceNeeded && !localVarsForClasses.containsKey(currentClass.getName())) {
				Local localVal = Jimple.v().newLocal("this", RefType.v(currentClass));
				Stmt s = Jimple.v().newIdentityStmt(localVal, Jimple.v().newThisRef((RefType) localVal.getType()));
				body.getUnits().add(s);

				if (localVal != null) {
					localVarsForClasses.put(currentClass.getName(), localVal);
					Local classLocal = localVarsForClasses.get(forClass);

					// Generate the lifecycles for the different kinds of
					// Android classes
					switch (componentType) {
					case Activity:
						generateActivityLifecycle(classSet, currentClass, endClassStmt, classLocal, body);
						break;
					case Service:
						generateServiceLifecycle(classSet, currentClass, endClassStmt, classLocal, body);
						break;
					case BroadcastReceiver:
						generateBroadcastReceiverLifecycle(classSet, currentClass, endClassStmt, classLocal, body);
						break;
					case ContentProvider:
						generateContentProviderLifecycle(classSet, currentClass, endClassStmt, classLocal, body);
						break;
					case Plain:
						// Allow the complete class to be skipped
						createIfStmt(endClassStmt, body);

						JNopStmt beforeClassStmt = new JNopStmt();
						body.getUnits().add(beforeClassStmt);
						for (SootMethod currentMethod : plainMethods.values()) {
							if (!currentMethod.isStatic() && classLocal == null) {
								logger.warn("Skipping method {} because we have no instance", currentMethod);
								continue;
							}

							// Create a conditional call on the current method
							JNopStmt thenStmt = new JNopStmt();
							createIfStmt(thenStmt, body);
							buildMethodCall(currentMethod, body, classLocal, generator);
							body.getUnits().add(thenStmt);

							// Because we don't know the order of the custom
							// statements, we assume that you can loop
							// arbitrarily
							createIfStmt(beforeClassStmt, body);
						}
						break;
					}
				}
			}
		} finally {
			body.getUnits().add(endClassStmt);
		}

		body.getUnits().add(Jimple.v().newReturnVoidStmt());

		// Optimize and check the generated main method
		NopEliminator.v().transform(body);
		eliminateSelfLoops(body);
		eliminateFallthroughIfs(body);

		if (DEBUG || Options.v().validate())
			emptySootMethod.getActiveBody().validate();

		return emptySootMethod;
	}

	/***** Util *****/

	private void eliminateFallthroughIfs(Body body) {
		boolean changed = false;
		do {
			changed = false;
			IfStmt ifs = null;
			Iterator<Unit> unitIt = body.getUnits().snapshotIterator();
			while (unitIt.hasNext()) {
				Unit u = unitIt.next();
				if (ifs != null && ifs.getTarget() == u) {
					body.getUnits().remove(ifs);
					changed = true;
				}
				ifs = null;
				if (u instanceof IfStmt)
					ifs = (IfStmt) u;
			}
		} while (changed);
	}

	private Stmt searchAndBuildMethod(String subsignature, SootClass currentClass, Set<String> entryPoints,
			Local classLocal, Body body) {
		if (currentClass == null || classLocal == null)
			return null;

		SootMethod method = findMethod(currentClass, subsignature);
		if (method == null) {
			logger.warn("Could not find Android entry point method: {}", subsignature);
			return null;
		}
		entryPoints.remove(method.getSignature());

		// If the method is in one of the predefined Android classes, it cannot
		// contain custom code, so we do not need to call it
		if (AndroidEntryPointConstants.isLifecycleClass(method.getDeclaringClass().getName()))
			return null;

		// If this method is part of the Android framework, we don't need to
		// call it
		if (method.getDeclaringClass().getName().startsWith("android."))
			return null;
		assert method.isStatic() || classLocal != null : "Class local was null for non-static method "
				+ method.getSignature();
		// write Method
		return buildMethodCall(method, body, classLocal, generator);
	}

	private void addApplicationCallbackMethods(Body body) {
		if (!this.callbackFunctions.containsKey(applicationClass.getName()))
			return;

		// Do not try to generate calls to methods in non-concrete classes
		if (applicationClass.isAbstract())
			return;
		if (applicationClass.isPhantom()) {
			System.err.println("Skipping possible application callbacks in " + "phantom class " + applicationClass);
			return;
		}

		for (String methodSig : this.callbackFunctions.get(applicationClass.getName())) {
			SootMethodAndClass methodAndClass = SootMethodRepresentationParser.v().parseSootMethodString(methodSig);

			// We do not consider lifecycle methods which are directly inserted
			// at their respective positions
			if (AndroidEntryPointConstants.getApplicationLifecycleMethods().contains(methodAndClass.getSubSignature()))
				continue;

			SootMethod method = findMethod(Scene.v().getSootClass(methodAndClass.getClassName()),
					methodAndClass.getSubSignature());
			// If we found no implementation or if the implementation we found
			// is in a system class, we skip it. Note that null methods may
			// happen since all callback interfaces for application callbacks
			// are registered under the name of the application class.
			if (method == null)
				continue;
			if (method.getDeclaringClass().getName().startsWith("android.")
					|| method.getDeclaringClass().getName().startsWith("java."))
				continue;

			// Get the local instance of the target class
			Local local = this.localVarsForClasses.get(methodAndClass.getClassName());
			if (local == null) {
				System.err.println(
						"Could not create call to application callback " + method.getSignature() + ". Local was null.");
				continue;
			}

			// Add a conditional call to the method
			JNopStmt thenStmt = new JNopStmt();
			createIfStmt(thenStmt, body);
			buildMethodCall(method, body, local, generator);
			body.getUnits().add(thenStmt);
		}
	}

	private boolean addCallbackMethods(SootClass currentClass, Set<SootClass> referenceClasses,
			String callbackSignature, Body body) {
		// If no callbacks are declared for the current class, there is nothing
		// to be done here
		if (currentClass == null)
			return false;
		if (!this.callbackFunctions.containsKey(currentClass.getName()))
			return false;

		// Get all classes in which callback methods are declared
		boolean callbackFound = false;
		Map<SootClass, Set<SootMethod>> callbackClasses = new HashMap<SootClass, Set<SootMethod>>();
		for (String methodSig : this.callbackFunctions.get(currentClass.getName())) {

			// Parse the callback
			SootMethodAndClass methodAndClass = SootMethodRepresentationParser.v().parseSootMethodString(methodSig);
			if (!callbackSignature.isEmpty() && !callbackSignature.equals(methodAndClass.getSubSignature()))
				continue;

			Scene.v().forceResolve(methodAndClass.getClassName(), SootClass.SIGNATURES);
			SootClass theClass = Scene.v().getSootClass(methodAndClass.getClassName());

			SootMethod theMethod = findMethod(theClass, methodAndClass.getSubSignature());
			if (theMethod == null) {
				// logger.warn("Could not find callback method {}",
				// methodAndClass.getSignature());
				continue;
			}

			// Check that we don't have one of the lifecycle methods as they are
			// treated separately.
			if (getComponentType(theClass) == ComponentType.Activity
					&& AndroidEntryPointConstants.getActivityLifecycleMethods().contains(theMethod.getSubSignature()))
				continue;
			if (getComponentType(theClass) == ComponentType.Service
					&& AndroidEntryPointConstants.getServiceLifecycleMethods().contains(theMethod.getSubSignature()))
				continue;
			if (getComponentType(theClass) == ComponentType.BroadcastReceiver
					&& AndroidEntryPointConstants.getBroadcastLifecycleMethods().contains(theMethod.getSubSignature()))
				continue;
			if (getComponentType(theClass) == ComponentType.ContentProvider && AndroidEntryPointConstants
					.getContentproviderLifecycleMethods().contains(theMethod.getSubSignature()))
				continue;

			if (callbackClasses.containsKey(theClass))
				callbackClasses.get(theClass).add(theMethod);
			else {
				Set<SootMethod> methods = new HashSet<SootMethod>();
				methods.add(theMethod);
				callbackClasses.put(theClass, methods);
			}
		}

		// The class for which we are generating the lifecycle always has an
		// instance.
		if (referenceClasses == null || referenceClasses.isEmpty())
			referenceClasses = Collections.singleton(currentClass);
		else {
			referenceClasses = new HashSet<SootClass>(referenceClasses);
			referenceClasses.add(currentClass);
		}

		Stmt beforeCallbacks = Jimple.v().newNopStmt();
		body.getUnits().add(beforeCallbacks);

		for (SootClass callbackClass : callbackClasses.keySet()) {

			// If we already have a parent class that defines this callback, we
			// use it. Otherwise, we create a new one.
			Set<Local> classLocals = new HashSet<Local>();
			for (SootClass parentClass : referenceClasses) {
				Local parentLocal = this.localVarsForClasses.get(parentClass.getName());
				if (isCompatible(parentClass, callbackClass))
					classLocals.add(parentLocal);
			}
			if (classLocals.isEmpty()) {
				// Create a new instance of this class
				// if we need to call a constructor, we insert the respective
				// Jimple statement here
				Local classLocal = generateClassConstructor(callbackClass, body, referenceClasses);
				if (classLocal == null) {
					logger.warn("Constructor cannot be generated for callback class {}", callbackClass.getName());
					continue;
				}
				classLocals.add(classLocal);
			}

			// Build the calls to all callback methods in this class
			for (Local classLocal : classLocals) {
				for (SootMethod callbackMethod : callbackClasses.get(callbackClass)) {
					JNopStmt thenStmt = new JNopStmt();
					createIfStmt(thenStmt, body);
					buildMethodCall(callbackMethod, body, classLocal, generator, referenceClasses);
					body.getUnits().add(thenStmt);
				}
				callbackFound = true;
			}
		}
		// jump back since we don't now the order of the callbacks
		if (callbackFound)
			createIfStmt(beforeCallbacks, body);

		return callbackFound;
	}

	private boolean createPlainMethodCall(Local classLocal, SootMethod currentMethod, Body body) {
		// Do not create calls to lifecycle methods which we handle explicitly
		if (AndroidEntryPointConstants.getServiceLifecycleMethods().contains(currentMethod.getSubSignature()))
			return false;

		JNopStmt beforeStmt = new JNopStmt();
		JNopStmt thenStmt = new JNopStmt();
		body.getUnits().add(beforeStmt);
		createIfStmt(thenStmt, body);
		buildMethodCall(currentMethod, body, classLocal, generator);

		body.getUnits().add(thenStmt);
		createIfStmt(beforeStmt, body);
		return true;
	}

	@Override
	protected SootMethod findMethod(SootClass currentClass, String subsignature) {
		if (currentClass.declaresMethod(subsignature)) {
			return currentClass.getMethod(subsignature);
		}
		if (currentClass.hasSuperclass()) {
			return findMethod(currentClass.getSuperclass(), subsignature);
		}
		return null;
	}

	private void createIfStmt(Unit target, Body body) {
		if (target == null) {
			return;
		}
		JEqExpr cond = new JEqExpr(intCounter, IntConstant.v(conditionCounter++));
		JIfStmt ifStmt = new JIfStmt(cond, target);
		body.getUnits().add(ifStmt);
	}

	private boolean isGCMBaseIntentService(SootClass currentClass) {
		while (currentClass.hasSuperclass()) {
			if (currentClass.getSuperclass().getName().equals(AndroidEntryPointConstants.GCMBASEINTENTSERVICECLASS))
				return true;
			currentClass = currentClass.getSuperclass();
		}
		return false;
	}

	private boolean isGCMListenerService(SootClass currentClass) {
		while (currentClass.hasSuperclass()) {
			if (currentClass.getSuperclass().getName().equals(AndroidEntryPointConstants.GCMLISTENERSERVICECLASS))
				return true;
			currentClass = currentClass.getSuperclass();
		}
		return false;
	}

	private ComponentType getComponentType(SootClass currentClass) {
		if (componentTypeCache.containsKey(currentClass))
			return componentTypeCache.get(currentClass);

		// Check the type of this class
		ComponentType ctype = ComponentType.Plain;
		List<SootClass> extendedClasses = Scene.v().getActiveHierarchy().getSuperclassesOf(currentClass);
		for (SootClass sc : extendedClasses) {
			if (sc.getName().equals(AndroidEntryPointConstants.APPLICATIONCLASS))
				ctype = ComponentType.Application;
			else if (sc.getName().equals(AndroidEntryPointConstants.ACTIVITYCLASS))
				ctype = ComponentType.Activity;
			else if (sc.getName().equals(AndroidEntryPointConstants.SERVICECLASS))
				ctype = ComponentType.Service;
			else if (sc.getName().equals(AndroidEntryPointConstants.BROADCASTRECEIVERCLASS))
				ctype = ComponentType.BroadcastReceiver;
			else if (sc.getName().equals(AndroidEntryPointConstants.CONTENTPROVIDERCLASS))
				ctype = ComponentType.ContentProvider;
			else
				continue;

			// As soon was we have found one matching parent class, we abort
			break;
		}
		componentTypeCache.put(currentClass, ctype);
		return ctype;
	}

	@Override
	protected SootMethod createDummyMainInternal(SootMethod emptySootMethod) {
		throw new RuntimeException("Dummy main should not be used for " + this.getClass());
	}

	/***** LIFECYCLES *****/

	private void generateActivityLifecycle(Set<String> entryPoints, SootClass currentClass, JNopStmt endClassStmt,
			Local classLocal, Body body) {

		// As we don't know the order in which the different Android lifecycles
		// run, we allow for each single one of them to be skipped
		createIfStmt(endClassStmt, body);

		Set<SootClass> referenceClasses = new HashSet<SootClass>();
		if (applicationClass != null)
			referenceClasses.add(applicationClass);
		for (SootClass callbackClass : this.applicationCallbackClasses)
			referenceClasses.add(callbackClass);
		referenceClasses.add(currentClass);

		// 1. onCreate:
		Stmt onCreateStmt = new JNopStmt();
		body.getUnits().add(onCreateStmt);
		{
			Stmt onCreateStmt2 = searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONCREATE, currentClass,
					entryPoints, classLocal, body);
			boolean found = addCallbackMethods(applicationClass, referenceClasses,
					AndroidEntryPointConstants.APPLIFECYCLECALLBACK_ONACTIVITYCREATED, body);
			if (found && onCreateStmt2 != null)
				createIfStmt(onCreateStmt2, body);
		}

		// 2. onStart:
		Stmt onStartStmt = new JNopStmt();
		body.getUnits().add(onStartStmt);
		{
			Stmt onStartStmt2 = searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONSTART, currentClass,
					entryPoints, classLocal, body);
			boolean found = addCallbackMethods(applicationClass, referenceClasses,
					AndroidEntryPointConstants.APPLIFECYCLECALLBACK_ONACTIVITYSTARTED, body);
			if (found && onStartStmt2 != null)
				createIfStmt(onStartStmt2, body);
		}
		searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONRESTOREINSTANCESTATE, currentClass, entryPoints,
				classLocal, body);
		searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONPOSTCREATE, currentClass, entryPoints, classLocal,
				body);

		// 3. onResume:
		Stmt onResumeStmt = new JNopStmt();
		body.getUnits().add(onResumeStmt);
		{
			Stmt onResumeStmt2 = searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONRESUME, currentClass,
					entryPoints, classLocal, body);
			boolean found = addCallbackMethods(applicationClass, referenceClasses,
					AndroidEntryPointConstants.APPLIFECYCLECALLBACK_ONACTIVITYRESUMED, body);
			if (found && onResumeStmt2 != null)
				createIfStmt(onResumeStmt2, body);
		}
		searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONPOSTRESUME, currentClass, entryPoints, classLocal,
				body);

		// Scan for other entryPoints of this class:
		Set<SootMethod> methodsToInvoke = new HashSet<SootMethod>();
		if (modelAdditionalMethods)
			for (SootMethod currentMethod : currentClass.getMethods())
				if (entryPoints.contains(currentMethod.toString()) && !AndroidEntryPointConstants
						.getActivityLifecycleMethods().contains(currentMethod.getSubSignature()))
					methodsToInvoke.add(currentMethod);
		boolean hasCallbacks = this.callbackFunctions.containsKey(currentClass.getName());

		if (!methodsToInvoke.isEmpty() || hasCallbacks) {
			JNopStmt startWhileStmt = new JNopStmt();
			JNopStmt endWhileStmt = new JNopStmt();
			body.getUnits().add(startWhileStmt);
			createIfStmt(endWhileStmt, body);

			// Add the callbacks
			addCallbackMethods(currentClass, null, "", body);

			// Add the other entry points
			boolean hasAdditionalMethods = false;
			for (SootMethod currentMethod : currentClass.getMethods())
				if (entryPoints.contains(currentMethod.toString()))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, currentMethod, body);

			body.getUnits().add(endWhileStmt);
			if (hasAdditionalMethods)
				createIfStmt(startWhileStmt, body);
		}

		// 4. onPause:
		Stmt onPause = searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONPAUSE, currentClass, entryPoints,
				classLocal, body);
		boolean hasAppOnPause = addCallbackMethods(applicationClass, referenceClasses,
				AndroidEntryPointConstants.APPLIFECYCLECALLBACK_ONACTIVITYPAUSED, body);
		if (hasAppOnPause && onPause != null)
			createIfStmt(onPause, body);
		searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONCREATEDESCRIPTION, currentClass, entryPoints,
				classLocal, body);
		Stmt onSaveInstance = searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONSAVEINSTANCESTATE,
				currentClass, entryPoints, classLocal, body);
		boolean hasAppOnSaveInstance = addCallbackMethods(applicationClass, referenceClasses,
				AndroidEntryPointConstants.APPLIFECYCLECALLBACK_ONACTIVITYSAVEINSTANCESTATE, body);
		if (hasAppOnSaveInstance && onSaveInstance != null)
			createIfStmt(onSaveInstance, body);

		// goTo Stop, Resume or Create:
		// (to stop is fall-through, no need to add)
		createIfStmt(onResumeStmt, body);
		// createIfStmt(onCreateStmt); // no, the process gets killed in between

		// 5. onStop:
		Stmt onStop = searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONSTOP, currentClass, entryPoints,
				classLocal, body);
		boolean hasAppOnStop = addCallbackMethods(applicationClass, referenceClasses,
				AndroidEntryPointConstants.APPLIFECYCLECALLBACK_ONACTIVITYSTOPPED, body);
		if (hasAppOnStop && onStop != null)
			createIfStmt(onStop, body);

		// goTo onDestroy, onRestart or onCreate:
		// (to restart is fall-through, no need to add)
		JNopStmt stopToDestroyStmt = new JNopStmt();
		createIfStmt(stopToDestroyStmt, body);
		// createIfStmt(onCreateStmt); // no, the process gets killed in between

		// 6. onRestart:
		searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONRESTART, currentClass, entryPoints, classLocal,
				body);
		createIfStmt(onStartStmt, body); // jump to onStart(), fall through to
		// onDestroy()

		// 7. onDestroy
		body.getUnits().add(stopToDestroyStmt);
		Stmt onDestroy = searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONDESTROY, currentClass, entryPoints,
				classLocal, body);
		boolean hasAppOnDestroy = addCallbackMethods(applicationClass, referenceClasses,
				AndroidEntryPointConstants.APPLIFECYCLECALLBACK_ONACTIVITYDESTROYED, body);
		if (hasAppOnDestroy && onDestroy != null)
			createIfStmt(onDestroy, body);

		createIfStmt(endClassStmt, body);
	}

	private void generateServiceLifecycle(Set<String> entryPoints, SootClass currentClass, JNopStmt endClassStmt,
			Local classLocal, Body body) {
		final boolean isGCMBaseIntentService = isGCMBaseIntentService(currentClass);
		final boolean isGCMListenerService = !isGCMBaseIntentService && isGCMListenerService(currentClass);

		// 1. onCreate:
		searchAndBuildMethod(AndroidEntryPointConstants.SERVICE_ONCREATE, currentClass, entryPoints, classLocal, body);

		// service has two different lifecycles:
		// lifecycle1:
		// 2. onStart:
		searchAndBuildMethod(AndroidEntryPointConstants.SERVICE_ONSTART1, currentClass, entryPoints, classLocal, body);

		// onStartCommand can be called an arbitrary number of times, or never
		JNopStmt beforeStartCommand = new JNopStmt();
		JNopStmt afterStartCommand = new JNopStmt();
		body.getUnits().add(beforeStartCommand);
		createIfStmt(afterStartCommand, body);

		searchAndBuildMethod(AndroidEntryPointConstants.SERVICE_ONSTART2, currentClass, entryPoints, classLocal, body);
		createIfStmt(beforeStartCommand, body);
		body.getUnits().add(afterStartCommand);

		// methods:
		// all other entryPoints of this class:
		JNopStmt startWhileStmt = new JNopStmt();
		JNopStmt endWhileStmt = new JNopStmt();
		body.getUnits().add(startWhileStmt);
		createIfStmt(endWhileStmt, body);

		boolean hasAdditionalMethods = false;
		if (modelAdditionalMethods) {
			for (SootMethod currentMethod : currentClass.getMethods())
				if (entryPoints.contains(currentMethod.toString()))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, currentMethod, body);
		}
		if (isGCMBaseIntentService) {
			for (String sig : AndroidEntryPointConstants.getGCMIntentServiceMethods()) {
				SootMethod sm = findMethod(currentClass, sig);
				if (sm != null && !sm.getDeclaringClass().getName()
						.equals(AndroidEntryPointConstants.GCMBASEINTENTSERVICECLASS))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, sm, body);
			}
		} else if (isGCMListenerService) {
			for (String sig : AndroidEntryPointConstants.getGCMListenerServiceMethods()) {
				SootMethod sm = findMethod(currentClass, sig);
				if (sm != null
						&& !sm.getDeclaringClass().getName().equals(AndroidEntryPointConstants.GCMLISTENERSERVICECLASS))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, sm, body);
			}
		}
		addCallbackMethods(currentClass, null, "", body);
		body.getUnits().add(endWhileStmt);
		if (hasAdditionalMethods)
			createIfStmt(startWhileStmt, body);

		// lifecycle1 end

		// lifecycle2 start
		// onBind:
		searchAndBuildMethod(AndroidEntryPointConstants.SERVICE_ONBIND, currentClass, entryPoints, classLocal, body);

		JNopStmt beforemethodsStmt = new JNopStmt();
		body.getUnits().add(beforemethodsStmt);
		// methods
		JNopStmt startWhile2Stmt = new JNopStmt();
		JNopStmt endWhile2Stmt = new JNopStmt();
		body.getUnits().add(startWhile2Stmt);
		hasAdditionalMethods = false;
		if (modelAdditionalMethods) {
			for (SootMethod currentMethod : currentClass.getMethods())
				if (entryPoints.contains(currentMethod.toString()))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, currentMethod, body);
		}
		if (isGCMBaseIntentService)
			for (String sig : AndroidEntryPointConstants.getGCMIntentServiceMethods()) {
				SootMethod sm = findMethod(currentClass, sig);
				if (sm != null && !sm.getName().equals(AndroidEntryPointConstants.GCMBASEINTENTSERVICECLASS))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, sm, body);
			}
		addCallbackMethods(currentClass, null, "", body);
		body.getUnits().add(endWhile2Stmt);
		if (hasAdditionalMethods)
			createIfStmt(startWhile2Stmt, body);

		// onUnbind:
		Stmt onDestroyStmt = Jimple.v().newNopStmt();
		searchAndBuildMethod(AndroidEntryPointConstants.SERVICE_ONUNBIND, currentClass, entryPoints, classLocal, body);
		createIfStmt(onDestroyStmt, body); // fall through to rebind or go to
											// destroy

		// onRebind:
		searchAndBuildMethod(AndroidEntryPointConstants.SERVICE_ONREBIND, currentClass, entryPoints, classLocal, body);
		createIfStmt(beforemethodsStmt, body);

		// lifecycle2 end

		// onDestroy:
		body.getUnits().add(onDestroyStmt);
		searchAndBuildMethod(AndroidEntryPointConstants.SERVICE_ONDESTROY, currentClass, entryPoints, classLocal, body);

		// either begin or end or next class:
		// createIfStmt(onCreateStmt); // no, the process gets killed in between
	}

	private void generateBroadcastReceiverLifecycle(Set<String> entryPoints, SootClass currentClass,
			JNopStmt endClassStmt, Local classLocal, Body body) {
		// As we don't know the order in which the different Android lifecycles
		// run, we allow for each single one of them to be skipped
		createIfStmt(endClassStmt, body);

		Stmt onReceiveStmt = searchAndBuildMethod(AndroidEntryPointConstants.BROADCAST_ONRECEIVE, currentClass,
				entryPoints, classLocal, body);
		// methods
		JNopStmt startWhileStmt = new JNopStmt();
		JNopStmt endWhileStmt = new JNopStmt();
		body.getUnits().add(startWhileStmt);
		createIfStmt(endWhileStmt, body);

		boolean hasAdditionalMethods = false;
		if (modelAdditionalMethods) {
			for (SootMethod currentMethod : currentClass.getMethods())
				if (entryPoints.contains(currentMethod.toString()))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, currentMethod, body);
		}

		addCallbackMethods(currentClass, null, "", body);
		body.getUnits().add(endWhileStmt);
		if (hasAdditionalMethods)
			createIfStmt(startWhileStmt, body);
		createIfStmt(onReceiveStmt, body);
	}

	private void generateContentProviderLifecycle(Set<String> entryPoints, SootClass currentClass,
			JNopStmt endClassStmt, Local classLocal, Body body) {
		// As we don't know the order in which the different Android lifecycles
		// run, we allow for each single one of them to be skipped
		createIfStmt(endClassStmt, body);

		// ContentProvider.onCreate() runs before everything else, even before
		// Application.onCreate(). We must thus handle it elsewhere.
		// Stmt onCreateStmt =
		// searchAndBuildMethod(AndroidEntryPointConstants.CONTENTPROVIDER_ONCREATE,
		// currentClass, entryPoints, classLocal);

		// see:
		// http://developer.android.com/reference/android/content/ContentProvider.html
		// methods
		JNopStmt startWhileStmt = new JNopStmt();
		JNopStmt endWhileStmt = new JNopStmt();
		body.getUnits().add(startWhileStmt);
		createIfStmt(endWhileStmt, body);

		boolean hasAdditionalMethods = false;
		if (modelAdditionalMethods) {
			for (SootMethod currentMethod : currentClass.getMethods())
				if (entryPoints.contains(currentMethod.toString()))
					hasAdditionalMethods |= createPlainMethodCall(classLocal, currentMethod, body);
		}
		addCallbackMethods(currentClass, null, "", body);
		body.getUnits().add(endWhileStmt);
		if (hasAdditionalMethods)
			createIfStmt(startWhileStmt, body);
		// createIfStmt(onCreateStmt);
	}
}
