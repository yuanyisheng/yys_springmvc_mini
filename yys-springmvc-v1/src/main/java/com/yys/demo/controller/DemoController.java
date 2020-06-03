package com.yys.demo.controller;

import com.yys.demo.service.IDemoService;
import com.yys.mvcframework.annotation.YysAutowired;
import com.yys.mvcframework.annotation.YysController;
import com.yys.mvcframework.annotation.YysRequestMapping;
import com.yys.mvcframework.annotation.YysRequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Controller
 * @author yys
 */
@YysController
@YysRequestMapping("/yys")
public class DemoController {

    @YysAutowired
    private IDemoService demoService;

    @YysRequestMapping("/query")
    public String query(HttpServletRequest request,
                        HttpServletResponse response,
                        @YysRequestParam String name) {
        String result = demoService.get(name);
        try {
            response.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    @YysRequestMapping("/add")
    public void add(HttpServletRequest request,
                    HttpServletResponse response,
                    @YysRequestParam Integer a,
                    @YysRequestParam Integer b) {
        try {
            response.getWriter().write(a + "+" + b + "=" + (a +  b));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @YysRequestMapping("/remove")
    public void remove(HttpServletRequest request,
                       HttpServletResponse response,
                       @YysRequestParam Integer id) {
        try {
            response.getWriter().write("删除成功");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
