package io.github.selectionmenucontrol;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.Menu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "StaticFieldLeak"})
public final class SelectionMenuModule extends XposedModule {
    private static final long RULE_CACHE_MS = 1500L;
    private static volatile SystemRuleStore.Snapshot cachedRules = SystemRuleStore.Snapshot.empty();
    private static volatile long nextRefreshAt;
    private static volatile Context systemContext;
    private static volatile String lastRespondedProbeNonce;
    private static volatile int moduleAppId = -1;
    private static SystemMenuClassifier menuClassifier;
    private static long nextClassifierAttemptAt;
    private static boolean reportedClassifierInitializationFailure;
    private static volatile boolean appTextHookInstalled;
    private static volatile int cachedBuiltinMask;
    private static volatile long builtinNextRefreshAt;
    private static int recordedQueryHits;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(android.util.Log.INFO, "SelectionMenuControl",
                "Module loaded: systemServer=" + param.isSystemServer());
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader loader = param.getClassLoader();
        int internalHooks = 0;
        if (isXiaomiRom()) {
            try {
                internalHooks = installHyperOsQueryHooks(loader);
            } catch (Throwable error) {
                log(android.util.Log.WARN, "SelectionMenuControl",
                        "HyperOS ComputerEngine hook scan failed; trying public query boundary", error);
            }
        }

        // Install the public boundary independently. A ROM can bypass the intercepted
        // internal overload even when it exists, causing the previous version to appear
        // enabled but never reach the menu query.
        boolean publicHook = false;
        try {
            Class<?> packageManager = Class.forName(
                    "com.android.server.pm.IPackageManagerBase", false, loader);
            for (Method query : packageManager.getDeclaredMethods()) {
                Class<?>[] types = query.getParameterTypes();
                if (!"queryIntentActivities".equals(query.getName())
                        || types.length != 4
                        || types[0] != Intent.class
                        || types[1] != String.class
                        || types[2] != long.class
                        || types[3] != int.class) continue;
                Class<?> sliceClass = Class.forName("android.content.pm.ParceledListSlice", false, loader);
                Method getList = sliceClass.getMethod("getList");
                Constructor<?> newSlice = sliceClass.getConstructor(List.class);
                hook(query).intercept(chain -> interceptPublicProcessTextQuery(chain,
                        sliceClass, getList, newSlice));
                publicHook = true;
                break;
            }
        } catch (Throwable error) {
            log(android.util.Log.WARN, "SelectionMenuControl",
                    "Public PROCESS_TEXT query boundary unavailable", error);
        }
        log(android.util.Log.INFO, "SelectionMenuControl",
                "System server Hook install complete: internal=" + internalHooks
                        + ", public=" + publicHook + ", manufacturer=" + Build.MANUFACTURER);
        if (internalHooks == 0 && !publicHook) {
            log(android.util.Log.ERROR, "SelectionMenuControl",
                    "No PROCESS_TEXT query hook installed; module will not affect menus");
        }
    }

    private static synchronized void recordQueryHit(String source, int originalCount) {
        if (recordedQueryHits < 8) {
            recordedQueryHits++;
            android.util.Log.i("SelectionMenuControl", "PROCESS_TEXT query hit: source="
                    + source + ", candidates=" + originalCount
                    + ", hit=" + recordedQueryHits);
        }
    }

    private static boolean isXiaomiRom() {
        String maker = Build.MANUFACTURER;
        return maker != null && (maker.equalsIgnoreCase("Xiaomi")
                || maker.equalsIgnoreCase("Redmi") || maker.equalsIgnoreCase("POCO"));
    }

    private int installHyperOsQueryHooks(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> engine = Class.forName("com.android.server.pm.ComputerEngine", false, loader);
        int installed = 0;
        for (Method method : engine.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"queryIntentActivitiesInternal".equals(method.getName())
                    || types.length < 4
                    || types[0] != Intent.class
                    || !List.class.isAssignableFrom(method.getReturnType())) continue;
            if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;

            int startFlagIndex = -1;
            if (types.length >= 2 && types[types.length - 2] == boolean.class
                    && types[types.length - 1] == boolean.class) {
                startFlagIndex = types.length - 2;
            }
            final int effectiveStartFlagIndex = startFlagIndex;
            try {
                hook(method).intercept(chain ->
                        interceptHyperOsQuery(chain, effectiveStartFlagIndex));
                installed++;
                log(android.util.Log.INFO, "SelectionMenuControl",
                        "Hooked HyperOS ComputerEngine overload: " + method.toGenericString());
            } catch (Throwable hookFailure) {
                log(android.util.Log.WARN, "SelectionMenuControl",
                        "Could not hook ComputerEngine overload " + method.toGenericString(), hookFailure);
            }
        }
        if (installed == 0) {
            throw new ReflectiveOperationException("No compatible ComputerEngine query overload hooked");
        }
        return installed;
    }

    private Object interceptHyperOsQuery(XposedInterface.Chain chain, int startFlagIndex) throws Throwable {
        Object result = chain.proceed();
        List<Object> args = chain.getArgs();
        if (args.isEmpty() || !(args.get(0) instanceof Intent)
                || !(result instanceof List<?>)) return result;
        Intent intent = (Intent) args.get(0);
        if (!Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())
                || intent.getComponent() != null || intent.getPackage() != null
                || (startFlagIndex >= 0 && Boolean.TRUE.equals(args.get(startFlagIndex))))
            return result;
        recordQueryHit("ComputerEngine", ((List<?>) result).size());

        boolean originalQuery = isOriginalQuery(intent);
        SystemRuleStore.Snapshot rules = readRules();
        if (!originalQuery && (!rules.valid ||
                (rules.hiddenComponents.isEmpty() && rules.orderedComponents.isEmpty()))) {
            respondToProbe();
            return result;
        }
        try {
            List<ResolveInfo> original = new ArrayList<>(((List<?>) result).size());
            for (Object entry : (List<?>) result) {
                if (!(entry instanceof ResolveInfo)) return result;
                original.add((ResolveInfo) entry);
            }
            Map<ResolveInfo, Integer> classifications = new IdentityHashMap<>();
            if (originalQuery || !rules.orderedComponents.isEmpty()) {
                classifications = HyperOsMenuClassifier.evaluate(original).classifications;
            }
            if (originalQuery) {
                List<ResolveInfo> annotated = new ArrayList<>(original.size());
                for (ResolveInfo info : original) {
                    ResolveInfo copy = new ResolveInfo(info);
                    if (info.activityInfo != null) {
                        copy.activityInfo = new ActivityInfo(info.activityInfo);
                        copy.activityInfo.metaData = info.activityInfo.metaData == null
                                ? new Bundle() : new Bundle(info.activityInfo.metaData);
                        copy.activityInfo.metaData.putInt(SystemRuleStore.MENU_CLASSIFICATION_KEY,
                                classifications.getOrDefault(info, SystemMenuClassifier.UNKNOWN));
                    }
                    annotated.add(copy);
                }
                respondToProbe();
                return annotated;
            }
            Map<ResolveInfo, Integer> finalClassifications = classifications;
            List<ResolveInfo> filtered = rules.apply(original,
                    SelectionMenuModule::componentOf,
                    entry -> finalClassifications.getOrDefault(entry,
                            SystemMenuClassifier.UNKNOWN) != SystemMenuClassifier.ORDINARY);
            respondToProbe();
            return filtered.equals(original) ? result : filtered;
        } catch (Throwable error) {
            log(android.util.Log.WARN, "SelectionMenuControl",
                    "HyperOS PROCESS_TEXT query failed; stock result preserved", error);
            return result;
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!isXiaomiRom() || !param.isFirstPackage()
                || BuildConfig.APPLICATION_ID.equals(param.getPackageName())
                || appTextHookInstalled) return;
        log(android.util.Log.INFO, "SelectionMenuControl",
                "Attempting built-in menu hook for " + param.getPackageName());
        try {
            Class<?> callback = Class.forName("android.widget.Editor$TextActionModeCallback",
                    false, param.getDefaultClassLoader());
            Method create = callback.getDeclaredMethod("onCreateActionMode", ActionMode.class, Menu.class);
            Method prepare = callback.getDeclaredMethod("onPrepareActionMode", ActionMode.class, Menu.class);
            hook(create).intercept(chain -> interceptTextMenu(chain, true));
            hook(prepare).intercept(chain -> interceptTextMenu(chain, false));
            appTextHookInstalled = true;
            log(android.util.Log.INFO, "SelectionMenuControl",
                    "HyperOS built-in TextView menu hooks active in " + param.getPackageName());
        } catch (Throwable error) {
            log(android.util.Log.WARN, "SelectionMenuControl",
                    "HyperOS app menu hook unavailable in " + param.getPackageName(), error);
        }
    }

    private Object interceptTextMenu(XposedInterface.Chain chain, boolean creating) throws Throwable {
        Object result = chain.proceed();
        if (creating && Boolean.FALSE.equals(result)) return result;
        try {
            List<Object> arguments = chain.getArgs();
            if (arguments.size() < 2 || !(arguments.get(1) instanceof Menu)) return result;
            int mask = readBuiltinMask();
            if (mask == 0) return result;
            boolean changed = BuiltinMenuRules.hide((Menu) arguments.get(1), mask);
            if (changed && !creating) return true;
        } catch (Throwable error) {
            log(android.util.Log.WARN, "SelectionMenuControl", "Built-in menu hide failed", error);
        }
        return result;
    }

    private static int readBuiltinMask() {
        long now = SystemClock.elapsedRealtime();
        if (now < builtinNextRefreshAt) return cachedBuiltinMask;
        synchronized (SelectionMenuModule.class) {
            if (now < builtinNextRefreshAt) return cachedBuiltinMask;
            try {
                Class<?> thread = Class.forName("android.app.ActivityThread");
                Object application = thread.getMethod("currentApplication").invoke(null);
                if (application instanceof Context) {
                    int updated = BuiltinRuleStore.read((Context) application);
                    if (updated != cachedBuiltinMask) {
                        cachedBuiltinMask = updated;
                        android.util.Log.i("SelectionMenuControl",
                                "Built-in menu rules loaded: mask=" + updated);
                    }
                } else {
                    android.util.Log.w("SelectionMenuControl",
                            "Application context not ready for built-in menu settings");
                }
            } catch (Throwable error) {
                android.util.Log.w("SelectionMenuControl",
                        "Could not read built-in menu settings; using cached rules", error);
            }
            builtinNextRefreshAt = now + 2500L;
            return cachedBuiltinMask;
        }
    }

    private static Intent findIntentArgument(List<Object> arguments) {
        for (Object argument : arguments) {
            if (argument instanceof Intent) {
                return (Intent) argument;
            }
        }
        return null;
    }

    private static String componentOf(ResolveInfo info) {
        return info.activityInfo == null ? "" : new ComponentName(
                info.activityInfo.packageName, info.activityInfo.name).flattenToString();
    }

    private Object interceptPublicProcessTextQuery(XposedInterface.Chain chain, Class<?> sliceClass,
                                                   Method getList, Constructor<?> newSlice) throws Throwable {
        Intent intent = findIntentArgument(chain.getArgs());
        boolean processText = intent != null && Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
        Object result = chain.proceed();
        if (!processText || !sliceClass.isInstance(result)) {
            return result;
        }
        recordQueryHit("IPackageManagerBase", -1);
        boolean originalQuery = isOriginalQuery(intent);
        SystemRuleStore.Snapshot rules = readRules();
        if (!originalQuery && (!rules.valid || (rules.hiddenComponents.isEmpty() && rules.orderedComponents.isEmpty()))) {
            respondToProbe();
            return result;
        }
        try {
            Object contents = getList.invoke(result);
            if (!(contents instanceof List<?>)) {
                return result;
            }
            List<?> original = (List<?>) contents;
            List<ResolveInfo> resolved = new ArrayList<>(original.size());
            for (Object entry : original) {
                if (!(entry instanceof ResolveInfo)) {
                    return result;
                }
                resolved.add((ResolveInfo) entry);
            }
            Map<ResolveInfo, Integer> classifications = new IdentityHashMap<>();
            if (originalQuery || !rules.orderedComponents.isEmpty()) {
                long identity = Binder.clearCallingIdentity();
                try {
                    Context context = getSystemContext();
                    if (isXiaomiRom()) {
                        SystemMenuClassifier.Result policy = HyperOsMenuClassifier.evaluate(resolved);
                        classifications = policy.classifications;
                        if (originalQuery) resolved = policy.displayOrder;
                    } else {
                        SystemMenuClassifier classifier = getMenuClassifier();
                        if (classifier != null) {
                            SystemMenuClassifier.Result policy = classifier.evaluate(context, resolved, originalQuery);
                            classifications = policy.classifications;
                            if (originalQuery) resolved = policy.displayOrder;
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            if (originalQuery) {
                List<ResolveInfo> annotated = new ArrayList<>(resolved.size());
                for (ResolveInfo entry : resolved) {
                    ResolveInfo copy = new ResolveInfo(entry);
                    if (entry.activityInfo != null) {
                        copy.activityInfo = new ActivityInfo(entry.activityInfo);
                        copy.activityInfo.metaData = entry.activityInfo.metaData == null ? new Bundle()
                                : new Bundle(entry.activityInfo.metaData);
                        copy.activityInfo.metaData.putInt(SystemRuleStore.MENU_CLASSIFICATION_KEY,
                                classifications.getOrDefault(entry, SystemMenuClassifier.UNKNOWN));
                    }
                    annotated.add(copy);
                }
                Object annotatedResult = newSlice.newInstance(annotated);
                respondToProbe();
                return annotatedResult;
            }
            Map<ResolveInfo, Integer> effectiveClassifications = classifications;
            List<ResolveInfo> filtered = rules.apply(resolved, SelectionMenuModule::componentOf,
                    entry -> effectiveClassifications.getOrDefault(entry, SystemMenuClassifier.UNKNOWN) != SystemMenuClassifier.ORDINARY);
            // Keep the original slice and ResolveInfo objects untouched. No internal resolution
            // query is filtered or reordered, including resolveActivity with resolveForStart=false.
            Object effectiveResult = filtered.equals(original) ? result : newSlice.newInstance(filtered);
            // Respond only after the original query and any required result replacement succeed.
            respondToProbe();
            return effectiveResult;
        } catch (Throwable error) {
            log(android.util.Log.WARN, "SelectionMenuControl", "Public query menu rules failed; original list preserved", error);
            return result;
        }
    }

    private static synchronized SystemMenuClassifier getMenuClassifier() {
        if (menuClassifier == null && SystemClock.elapsedRealtime() >= nextClassifierAttemptAt) {
            nextClassifierAttemptAt = SystemClock.elapsedRealtime() + 5_000L;
            try {
                menuClassifier = new SystemMenuClassifier();
                android.util.Log.i("SelectionMenuControl", "Installed ROM menu policy classifier ready");
            } catch (Throwable error) {
                if (!reportedClassifierInitializationFailure) {
                    reportedClassifierInitializationFailure = true;
                    android.util.Log.w("SelectionMenuControl", "Installed ROM menu policy unavailable; hiding remains active, will retry", error);
                }
            }
        }
        return menuClassifier;
    }

    private static boolean isOriginalQuery(Intent intent) {
        try {
            if (!intent.getBooleanExtra(SystemRuleStore.QUERY_ORIGINAL_EXTRA, false)) {
                return false;
            }
            int callingAppId = Binder.getCallingUid() % 100_000;
            int appId = moduleAppId;
            if (appId < 0) {
                Context context = getSystemContext();
                if (context == null) {
                    return false;
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    appId = context.getPackageManager().getPackageUid(BuildConfig.APPLICATION_ID, 0) % 100_000;
                    moduleAppId = appId;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            // Only the module UI may enumerate the original list, including hidden activities.
            return callingAppId == appId;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static SystemRuleStore.Snapshot readRules() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextRefreshAt) {
            return cachedRules;
        }
        synchronized (SelectionMenuModule.class) {
            if (now < nextRefreshAt) {
                return cachedRules;
            }
            cachedRules = queryRules();
            nextRefreshAt = now + RULE_CACHE_MS;
            return cachedRules;
        }
    }

    private static SystemRuleStore.Snapshot queryRules() {
        Context context = getSystemContext();
        return context == null ? SystemRuleStore.Snapshot.empty() : SystemRuleStore.read(context);
    }

    private void respondToProbe() {
        synchronized (SelectionMenuModule.class) {
            Context context = getSystemContext();
            if (context != null) {
                String nonce = SystemRuleStore.respondToProbe(context, lastRespondedProbeNonce);
                if (nonce != null) {
                    lastRespondedProbeNonce = nonce;
                }
            }
        }
    }

    private static Context getSystemContext() {
        try {
            Context context = systemContext;
            if (context != null) {
                return context;
            }
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            context = (Context) getSystemContext.invoke(current.invoke(null));
            systemContext = context;
            return context;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
