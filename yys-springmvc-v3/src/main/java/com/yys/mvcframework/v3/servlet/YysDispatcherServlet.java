package com.yys.mvcframework.v3.servlet;

import com.yys.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * spring mvc 核心
 *      v2版本
 * @author yys
 */
public class YysDispatcherServlet extends HttpServlet {

    // 存储application.properties的配置内容信息
    private Properties contextConfig = new Properties();

    // 存储所有扫描到的类
    private List<String> classNames = new ArrayList<String>();

    // TODO：注册式单例模式
    // IOC容器(Spring中为 ConcurrentHashMap)
    private Map<String, Object> ioc = new HashMap<String, Object>();

    // (v3版本改进.)保存所有的url和方法的映射关系
//    private Map<String, Method> handlerMapping = new HashMap<String, Method>();
    private List<Handler> handlerMapping = new ArrayList<Handler>();


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            // TODO：委派模式
            this.doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Excetion Detail:" + Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * 1、配置阶段
     *      配置web.xml
     *      设置init-param: contextConfigLocation = classpath:application.xml
     *      设置url-pattern: /*
     *      配置Annotation: @YysController @YysService @YysRequestMapping @YysAutowired @YysRequestParam
     */


    /**
     * 2、初始化阶段
     * @param config
     * @throws ServletException
     */
    @Override
    public void init(ServletConfig config) throws ServletException {

        // TODO：模板模式

        // 1.加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        // 2.扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));

        // 3.初始化扫描到的类，并且放入IOC容器之中
        doInstance();

        // 4.DI注入，完成依赖注入
        doAutowired();

        // 5.初始化 HandlerMapping
        initHandlerMapping();

        System.out.println("Yys Spring framework is init !");

    }


    /**
     * 3、运行阶段
     * @param req
     * @param resp
     */
    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {

        // TODO ---------------改进版本---------------------------

        Handler handler = getHandler(req);
        if(handler == null) {
            resp.getWriter().write("404 Not Found !");
            return;
        }

        // 获取方法的形参列表
        Class<?>[] paramTypes = handler.method.getParameterTypes();

        // 保存所有需要自动赋值参数
        Object [] paramValues = new Object[paramTypes.length];

        // 设置方法中的参数
        Map<String, String[]> params = req.getParameterMap();
        for (Map.Entry<String, String[]> param : params.entrySet()) {
            if(!handler.paramIndexMapping.containsKey(param.getKey())) continue;

            String value = Arrays.toString(param.getValue())
                    .replaceAll("\\[|\\]", "")
                    .replaceAll("\\s", ",");
            Integer index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(paramTypes[index], value);
        }

        // 设置方法中的request对象
        String reqName = HttpServletRequest.class.getName();
        if(handler.paramIndexMapping.containsKey(reqName)) {
            Integer reqIndex = handler.paramIndexMapping.get(reqName);
            paramValues[reqIndex] = req;
        }

        // 设置方法中的response对象
        String respName = HttpServletResponse.class.getName();
        if(handler.paramIndexMapping.containsKey(respName)) {
            Integer respIndex = handler.paramIndexMapping.get(respName);
            paramValues[respIndex] = resp;
        }

        // 反射调用方法
        handler.method.invoke(handler.controller, paramValues);

        // TODO ---------------改进版本---------------------------

    }


    /*
     * 初始化 HandlerMapping
     */
    private void initHandlerMapping() {

        // return -> ioc为空
        if(this.ioc.isEmpty()) return;

        for (Map.Entry<String, Object> entry : this.ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if(!clazz.isAnnotationPresent(YysController.class)) continue;

            // 保存类上面的 @YysRequestMapping("/yys") -> yys
            String baseUrl = "";
            YysRequestMapping clazzRequestMapping = clazz.getAnnotation(YysRequestMapping.class);
            baseUrl = clazzRequestMapping.value().trim();

            // 获取所有 public 方法
            Method[] methods = clazz.getMethods();
            for (Method method : clazz.getMethods()) {
                if(!method.isAnnotationPresent(YysRequestMapping.class)) continue;

                // 保存方法上面的 @YysRequestMapping("/query") -> query
                YysRequestMapping methodRequestMapping = method.getAnnotation(YysRequestMapping.class);
                // 消除多斜杠，替换为单斜杠
                String url = ("/" + baseUrl + "/" + methodRequestMapping.value().trim()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(url);
//                handlerMapping.put(url, method);
                handlerMapping.add(new Handler(entry.getValue(), method, pattern));
                System.out.println("Mapped :" + url + "," + method);

            }
        }
    }

    /*
     * DI注入，完成依赖注入
     */
    private void doAutowired() {

        // return -> ioc为空
        if(this.ioc.isEmpty()) return;

        for (Map.Entry<String, Object> entry : this.ioc.entrySet()) {
            // Declared 表示所有的，特定的 字段，包括 private/protected/default
            Field[] fields = entry.getValue().getClass().getDeclaredFields();

            for (Field field : fields) {
                if(!field.isAnnotationPresent(YysAutowired.class)) continue;

                YysAutowired autowired = field.getAnnotation(YysAutowired.class);
                // 1.自定义beanName
                String beanName = autowired.value();
                // 2.接口的类型作为 key，待会拿这个 key 到 ioc 容器中去取值
                // field.getType().getName():com.yys.demo.service.IDemoService
                if("".equals(beanName)) beanName = field.getType().getName();

                try {
                    // 利用反射机制，动态给字段赋值
                    field.setAccessible(true); // 强吻
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }

        }
    }

    /*
     * 初始化扫描到的类，并且放入IOC容器之中
     */
    private void doInstance() {

        // return -> classNames为空
        if(this.classNames.isEmpty()) return;

        try {
            // 什么样的类才初始化？加了注解的类才初始化
            // 为了简化代码逻辑，主要体会设计思想，只举例 @Controller 和@Service，@Componment...就一一举例了

            for (String className : this.classNames) {
                Class<?> clazz = Class.forName(className);

                if(clazz.isAnnotationPresent(YysController.class)) {

                    // clazz.getSimpleName():DemoController
                    String beanName = this.toLowerFirstCase(clazz.getSimpleName().trim());
                    Object instance = clazz.newInstance();
                    this.ioc.put(beanName, instance); // ioc填充

                } else if(clazz.isAnnotationPresent(YysService.class)) {
                    YysService service = clazz.getAnnotation(YysService.class);

                    // 1.自定义beanName(eg: @YysService("yys") -> yys)
                    String beanName = service.value().trim();
                    // 2.类名(默认首字母小写)
                    if("".equals(beanName)) beanName = this.toLowerFirstCase(clazz.getSimpleName().trim());
                    Object instance = clazz.newInstance();
                    this.ioc.put(beanName, instance); // ioc填充

                    // 3.根据类型自动赋值，投机取巧方式
                    for (Class<?> i : clazz.getInterfaces()) {
                        // i.getName():com.yys.demo.service.IDemoService
                        beanName = i.getName().trim();
                        if(this.ioc.containsKey(beanName)) {
                            throw new Exception("The “" + i.getName() + "” is exists !");
                        }
                        // 接口类型作为key
                        this.ioc.put(beanName, instance);
                    }
                } else {
                    continue;
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }



    }

    /*
     * 扫描相关的类
     *      @param scanPackage:配置文件内容信息(com.yys.demo)
     */
    private void doScanner(String scanPackage) {

        // scanPackage=com.yys.demo，配置文件内容信息存储的是包路径
        // 需转换为文件路径，实际上是把.替换为/即可 (file:/D:/java/yys/yys-demo/yys-springmvc-mini/yys-springmvc-v1/target/yys-springmvc-v1/WEB-INF/classes/com/yys/demo/)
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            // 遍历文件，文件夹跳过
            if(file.isDirectory()) {
                doScanner(scanPackage + "." + file.getName());
            } else {
                // 跳过不为.class的文件
                if(!file.getName().endsWith(".class")) continue;

                // 包路径+文件名(类名)
                // file.getName():DemoController.class
                String className = scanPackage + "." + file.getName().replace(".class", "");
                this.classNames.add(className);
            }
        }


    }

    /*
     * 加载配置文件
     *      @param contextConfigLocation:web.xml contextConfigLocation 对应值
     */
    private void doLoadConfig(String contextConfigLocation) {

        // 直接从类路径下找到 spring 主配置文件所在的路径
        // 读取配置文件，内容信息放入Properties对象中
        // 相当于：scanPackage=com.yys.demo 从文件中保存到了内存中
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(null != fis) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    /* 获取handler对象 */
    private Handler getHandler(HttpServletRequest request) throws Exception {
        if(handlerMapping.isEmpty()) return null;

        String url = request.getRequestURI();
        String contextPath = request.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");

        for (Handler handler : handlerMapping) {

            Matcher matcher = handler.pattern.matcher(url);
            if(!matcher.matches()) continue;

            return handler;
        }
        return null;
    }

    // url传过来的参数都是String类型的，Http是基于字符串协议
    // 只需要把String转换为任意类型就好
    private Object convert(Class<?> paramType, String value) {
        if(Integer.class == paramType) {
            return Integer.valueOf(value);
        }
        // 如果还有double或者其他类型，继续加if
        // 这时我们应该想到策略模式了
        // 在这里暂时不实现..
        return value;
    }

    // 如果类名本身是小写字母，确实会出问题
    // 但是我要说明的是：这个方法是我自己用，private 的
    // 传值也是自己传，类也都遵循了驼峰命名法
    // 默认传入的值，存在首字母小写的情况，也不可能出现非字母的情况
    // 为了简化程序逻辑，就不做其他判断了，大家了解就 OK
    // 其实用写注释的时间都能够把逻辑写完了
    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray(); //之所以加，是因为大小写字母的 ASCII 码相差 32，
        // 而且大写字母的 ASCII 码要小于小写字母的 ASCII 码
        // 在 Java 中，对 char 做算学运算，实际上就是对 ASCII 码做算学运算
        chars[0] += 32;
        return String.valueOf(chars);
    }


    /**
     * 内部类
     *      Handler 记录 Controller 中的 RequestMapping 和 Method 的对应关系
     * @author yys
     */
    private class Handler {

        // 保存方法对应的实例
        protected Object controller;
        // 保存映射的方法
        protected Method method;
        // 正则匹配url
        protected Pattern pattern;
        // 参数位置顺序
        protected Map<String, Integer> paramIndexMapping;


        public Handler(Object controller, Method method, Pattern pattern) {
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;

            paramIndexMapping = new HashMap<String, Integer>();
            putParamIndexMapping(method);
        }


        /* 记录方法中参数的位置 */
        private void putParamIndexMapping(Method method) {

            // 提取方法中加了注解的参数
            Annotation[][] pa = method.getParameterAnnotations();
            for(int i = 0; i < pa.length; i++) {
                for (Annotation annotation : pa[i]) {
                    if(annotation instanceof YysRequestParam) {
                        String paramName = ((YysRequestParam) annotation).value().trim();
                        if(!"".equals(paramName)) {
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            // 提取方法中的request和response参数
            Class<?>[] parameterTypes = method.getParameterTypes();
            for(int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if(parameterType == HttpServletRequest.class || parameterType == HttpServletResponse.class) {
                    paramIndexMapping.put(parameterType.getName(), i);
                }
            }
        }
    }

}
