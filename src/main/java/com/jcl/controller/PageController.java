package com.jcl.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器：只负责返回可视化主页。
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
