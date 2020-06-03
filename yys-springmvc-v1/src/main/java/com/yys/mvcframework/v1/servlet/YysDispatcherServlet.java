package com.yys.mvcframework.v1.servlet;

import com.yys.mvcframework.annotation.YysAutowired;
import com.yys.mvcframework.annotation.YysController;
import com.yys.mvcframework.annotation.YysRequestMapping;
import com.yys.mvcframework.annotation.YysService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * spring mvc 核心
 *      v1版本
 * @author yys
 */
public class YysDispatcherServlet extends HttpServlet {

    Map<String, Object> handlerMapping = new HashMap<String, Object>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            this.doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exception " + Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {

        /**
         * 4、用户输入url,开始调用
         */
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");

        // 无对应url，404
        if(!this.handlerMapping.containsKey(url)) {
            resp.getWriter().write("404 Not Found !!!");
            return;
        }

        Method method = (Method) this.handlerMapping.get(url);
        // 获取方法参数
        Map<String, String[]> params = req.getParameterMap();
        // 包名+方法所属类名
        String name = method.getDeclaringClass().getName();
        // 通过反射调用方法
        // v1版本存在问题，参数写死
        method.invoke(this.handlerMapping.get(name),
                new Object[] {req, resp, params.get("name")[0]}
                );

    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        InputStream is = null;

        try {

            /**
             * 1、加载配置文件，读取url下文件存入handlerMapping
             */
            // 读取配置文件
            Properties properties = new Properties();
            is = this.getClass().getClassLoader().getResourceAsStream(config.getInitParameter("contextConfigLocation"));
            properties.load(is);
            // 加载配置文件数据
            String scanPackage = properties.getProperty("scanPackage");
            // 获取路径下对应文件，并存到handlerMapping中
            doScanner(scanPackage);

            /**
             * 2、加载文件，url对应Method映射，填充handlerMapping
             */
            // 遍历从配置文件url下加载的所有文件
            // 判断区分：@YysController @YysService
            for (String className : handlerMapping.keySet()) {
                if(!className.contains(".")) continue;

                Class<?> clazz = Class.forName(className);
                if(clazz.isAnnotationPresent(YysController.class)) {
                    // + YysController
                    // key:包名+文件名 value:类实例
                    handlerMapping.put(className, clazz.newInstance());

                    // + YysRequestMapping
                    // key:类RequestMapping url+方法RequestMapping url value:method
                    String baseUrl = "";
                    if(clazz.isAnnotationPresent(YysRequestMapping.class)) { // 判断是否有该注解
                        YysRequestMapping requestMapping = clazz.getAnnotation(YysRequestMapping.class); // 获取注解信息
                        baseUrl = requestMapping.value(); // 获取注解信息 @YysRequestMapping("/test")： /test
                    }

                    Method[] methods = clazz.getMethods();
                    for (Method method : methods) {
                        if(!method.isAnnotationPresent(YysRequestMapping.class)) continue;

                        YysRequestMapping requestMapping = method.getAnnotation(YysRequestMapping.class);
                        String url = (baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
                        handlerMapping.put(url, method);

                        System.out.println("Mapping " + url + "," + method);
                    }

                } else if(clazz.isAnnotationPresent(YysService.class)) {

                    // + YysService
                    // key: 1.注解信息别名 2.包名+类名
                    // value: 类实例
                    YysService service = clazz.getAnnotation(YysService.class);
                    String beanName = service.value();
                    if("".equals(beanName)) {
                        beanName = clazz.getName();
                    }
                    Object instance = clazz.newInstance();
                    handlerMapping.put(beanName, instance);

                    // + 实现类的接口
                    // key: 接口类名 value: 实现类实例
                    for (Class<?> i : clazz.getInterfaces()) {
                        handlerMapping.put(i.getName(), instance);
                    }

                } else {
                    continue;
                }

                /**
                 * 3、DI注入
                 */
                for (Object object : handlerMapping.values()) {
                    if(object == null) continue;

                    Class<?> calzz = object.getClass();
                    if(calzz.isAnnotationPresent(YysController.class)) {
                        Field[] fields = clazz.getDeclaredFields();
                        for (Field field : fields) {
                            if(!field.isAnnotationPresent(YysAutowired.class)) continue;

                            // @YysAutowired
                            // 注入
                            // 优先级：1.注解信息别名 2.包名+字段类型名
                            YysAutowired autowired = field.getAnnotation(YysAutowired.class);
                            String beanName = autowired.value();
                            if("".equals(beanName)) {
                                beanName = field.getType().getName();
                            }
                            field.setAccessible(true); // 强吻
                            field.set(handlerMapping.get(clazz.getName()), handlerMapping.get(beanName));
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();

        } finally {
            if(is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        System.out.println("Yys MVC Framework is init !!!");
    }

    // 获取配置文件url下对应的文件，并存入handlerMapping
    private void doScanner(String scanPackage) {
        String name = "/" + scanPackage.replaceAll("\\.", "/");
        URL url = this.getClass().getClassLoader().getResource(name);
        File classDir = new File(url.getFile());
        for (File file : classDir.listFiles()) {
            if(file.isDirectory()) {
                doScanner(scanPackage + "." + file.getName());
            } else {
                if(!file.getName().endsWith(".class")) continue;

                // package名 + 文件名(DemoController)
                String clazzName = scanPackage + "." + file.getName().replace(".class", "");
                handlerMapping.put(clazzName, null);
            }
        }
    }

}
