/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.common;

import java.util.HashMap;

public class Result extends HashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    public Result() {
        put("code", 200);
        put("msg", "操作成功");
    }

    public static Result success() {
        return new Result();
    }

    public static Result success(String msg) {
        Result result = new Result();
        result.put("msg", msg);
        return result;
    }

    public static Result success(Object data) {
        Result result = new Result();
        result.put("data", data);
        return result;
    }

    public static Result error() {
        Result result = new Result();
        result.put("code", 500);
        result.put("msg", "操作失败");
        return result;
    }

    public static Result error(String msg) {
        Result result = new Result();
        result.put("code", 500);
        result.put("msg", msg);
        return result;
    }

    public static Result error(int code, String msg) {
        Result result = new Result();
        result.put("code", code);
        result.put("msg", msg);
        return result;
    }

    @Override
    public Result put(String key, Object value) {
        super.put(key, value);
        return this;
    }
}
