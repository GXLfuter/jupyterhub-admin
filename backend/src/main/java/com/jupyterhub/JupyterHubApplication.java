/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JupyterHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(JupyterHubApplication.class, args);
    }
}
