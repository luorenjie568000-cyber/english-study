package com.englishstudy.app;

import android.app.Application;
import android.content.Context;

import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Vocab;

/** 全局入口：词典后台加载，生词本常驻内存。 */
public class App extends Application {

    private static App sInst;

    public static App get() {
        return sInst;
    }

    public static Context ctx() {
        return sInst;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        Vocab.init(this);
        new Thread(() -> Dict.load(this), "dict-loader").start();
    }
}
