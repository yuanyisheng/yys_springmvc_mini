package com.yys.demo.service.impl;

import com.yys.demo.service.IDemoService;
import com.yys.mvcframework.annotation.YysService;

@YysService
public class DemoService implements IDemoService {

    @Override
    public String get(String name) {
        return "My name is " + name;
    }

}
