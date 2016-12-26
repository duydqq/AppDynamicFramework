package com.dynamicload.framework.framework;


import com.dynamicload.framework.dynamicload.internal.DLIntent;
import com.dynamicload.framework.dynamicload.internal.DLPluginManager;
import com.dynamicload.framework.dynamicload.internal.DLPluginPackage;
import com.dynamicload.framework.dynamicload.utils.DLUtils;
import com.dynamicload.framework.framework.api.MicroApplicationContext;
import com.dynamicload.framework.framework.model.Bundle;
import com.dynamicload.framework.service.ServiceDescription;
import com.dynamicload.framework.util.FrameworkUtil;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * Created by android_mc on 16/5/4.
 */
public class MicroApplicationContextImpl implements MicroApplicationContext {

    private static String TAG = "MicroApplicationContext";

    public <T> T findServiceByInterface(String interfaceName) {

        String className = null;
        String bundleName = null;
        boolean lazy = true;
        for (ServiceDescription serviceDescription : BaseMetaInfo.services) {
            if (interfaceName.equals(serviceDescription.getInterfaceName())) {
                className = serviceDescription.getClassName();
                bundleName = serviceDescription.getBundleName();
                lazy = false;
                break;
            }
        }
        if (lazy) {
            //load lazy dex
            for (Map.Entry<String, Bundle> entry : FrameworkUtil.soPathMap.entrySet()) {
                Bundle bundle = entry.getValue();
                if (bundle.serviceName != null) {
                    for (String service : bundle.serviceName) {
                        if (interfaceName.endsWith(service)) {
                            FrameworkUtil.loadDexAndService(bundle.bundleName, bundle.soPath);
                            return findServiceByInterface(interfaceName);
                        }
                    }
                }
            }
        }
        if (bundleName == null) {
            Log.e(TAG, "not init service." + interfaceName);
            return null;
        }
        String apkRootPath = FrameworkUtil.getApkPathByBundleName(bundleName);
        Log.d(TAG, "apk root path:" + apkRootPath);
        DLPluginManager dlPluginManager = DLPluginManager.getInstance(FrameworkUtil.getContext());
        DLPluginPackage dlPluginPackage = dlPluginManager.loadApk(apkRootPath);

        Object object = null;
        try {
            DexClassLoader classLoader = dlPluginPackage.classLoader;
            //load class
            Class localClass = classLoader.loadClass(className);
            //construct instance
            Constructor localConstructor = localClass.getConstructor();
            object = localConstructor.newInstance();
        } catch (IllegalAccessException e) {
            Log.e(TAG, "IllegalAccessException ", e);
        } catch (InstantiationException e) {
            Log.e(TAG, "InstantiationException ", e);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "ClassNotFoundException ", e);
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
        if (object == null) {
            /**
             * Local Bundle Name
             */
            try {
                ClassLoader loader = MicroApplicationContextImpl.class.getClassLoader();
                Class implClass = loader.loadClass(className);
                Constructor metaInfoConStructor = implClass.getConstructor(new Class[]{});
                object = metaInfoConStructor.newInstance();
            } catch (Throwable e) {
                e.printStackTrace();
            }

        }

        if (object == null) {
            return null;
        }
        return (T) object;
    }

    public Class<?> getClass(String classPath) {
        Class<?> classtype = null;
        try {
            classtype = Class.forName(classPath);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "exception", e);
        }
        return classtype;
    }

    private DLPluginPackage getPluginPackageByBundle(String bundleName) {
        Context context = FrameworkUtil.getContext();
        String apkPath = FrameworkUtil.getApkPathByBundleName(bundleName);
        if (TextUtils.isEmpty(apkPath)) {
            Log.e(TAG, "bundleName error.");
            return null;
        }
        //load class
        PackageInfo info = DLUtils.getPackageInfo(context, apkPath);
        if (info == null) {
            return null;
        }
        return DLPluginManager.getInstance(context).getPackage(info.packageName);
    }

    public Resources getResourcesByBundle(String bundleName) {
        DLPluginPackage dlPluginPackage = getPluginPackageByBundle(bundleName);
        if (dlPluginPackage == null) {
            return null;
        }
        return dlPluginPackage.resources;
    }

    public AssetManager getAssetsByBundle(String bundleName) {
        DLPluginPackage dlPluginPackage = getPluginPackageByBundle(bundleName);
        if (dlPluginPackage == null) {
            return null;
        }
        return dlPluginPackage.assetManager;
    }

    public void startActivity(String bundleName, DLIntent intent) {
        DLPluginManager pluginManager = DLPluginManager.getInstance(FrameworkUtil.getContext());
        intent.setPluginPackage(getPluginPackageByBundle(bundleName).packageName);
        pluginManager.startPluginActivity(FrameworkUtil.getContext(), intent);
    }

    public Class<?> getClass(String bundleName, String classname) {
        DLPluginManager pluginManager = DLPluginManager.getInstance(FrameworkUtil.getContext());
        String packageName = getPluginPackageByBundle(bundleName).packageName;
        return pluginManager.getPluginClass(packageName, classname);
    }

    public void startActivityForResult(String bundleName, DLIntent intent, int requestCode) {
        startActivityForResult(bundleName, (Activity) FrameworkUtil.getContext(), intent, requestCode);
    }

    public void startActivityForResult(String bundleName, Activity activity, DLIntent intent, int requestCode) {
        DLPluginManager pluginManager = DLPluginManager.getInstance(FrameworkUtil.getContext());
        intent.setPluginPackage(getPluginPackageByBundle(bundleName).packageName);
        pluginManager.startPluginActivityForResult(activity, intent, requestCode);
    }

    public void startExtActivityForResult(DLIntent intent, int requestCode) {
        DLPluginManager pluginManager = DLPluginManager.getInstance(FrameworkUtil.getContext());
        pluginManager.performStartActivityForResult(FrameworkUtil.getContext(), intent, requestCode);
    }

    public void startExtActivity(DLIntent intent) {
        DLPluginManager pluginManager = DLPluginManager.getInstance(FrameworkUtil.getContext());
        pluginManager.performStartActivityForResult(FrameworkUtil.getContext(), intent, -1);
    }
}

