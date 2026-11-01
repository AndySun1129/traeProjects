package com.jcl.controller;

import com.jcl.model.ParseResult;
import com.jcl.service.JclParserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON API 控制器。
 * <p>
 * 与页面控制器分开，使用 {@link RestController} 和构造器注入。
 */
@RestController
public class JclApiController {

    private final JclParserService jclParserService;

    public JclApiController(JclParserService jclParserService) {
        this.jclParserService = jclParserService;
    }

    /**
     * 解析 JCL 源码。
     *
     * @param request 请求体，包含 jclSource 字段
     * @return 解析结果 JSON，包含 jobs / errors / warnings
     */
    @PostMapping("/api/parse")
    public ParseResult parse(@RequestBody(required = false) ParseRequest request) {
        String source = request == null ? null : request.getJclSource();
        return jclParserService.parse(source);
    }

    public static class ParseRequest {
        private String jclSource;

        public String getJclSource() {
            return jclSource;
        }

        public void setJclSource(String jclSource) {
            this.jclSource = jclSource;
        }
    }
}
