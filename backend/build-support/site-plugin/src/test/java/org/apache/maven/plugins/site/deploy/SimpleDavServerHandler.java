/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.site.deploy;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.jetty.ee8.nested.AbstractHandler;
import org.eclipse.jetty.ee8.nested.ContextHandler;
import org.eclipse.jetty.ee8.nested.Handler;
import org.eclipse.jetty.ee8.nested.Request;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

/**
 * @author Olivier Lamy
 * @since 3.0-beta-2
 *
 */
public class SimpleDavServerHandler {

    private Server server;

    private File siteTargetPath;

    List<HttpRequest> httpRequests = new ArrayList<>();

    public SimpleDavServerHandler(final File targetPath) throws Exception {
        this.siteTargetPath = targetPath;
        Handler repoHandler = new AbstractHandler() {
            @Override
            public void handle(String target, Request r, HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException {
                String targetPath = request.getPathInfo();

                HttpRequest rq = new HttpRequest();
                rq.method = request.getMethod();
                rq.path = targetPath;

                @SuppressWarnings("rawtypes")
                Enumeration headerNames = request.getHeaderNames();
                while (headerNames.hasMoreElements()) {
                    String name = (String) headerNames.nextElement();
                    rq.headers.put(name, request.getHeader(name));
                }

                httpRequests.add(rq);

                if (request.getMethod().equalsIgnoreCase("PUT")) {
                    File targetFile = new File(siteTargetPath, targetPath);
                    targetFile.getParentFile().mkdirs();
                    Files.copy(request.getInputStream(), targetFile.toPath());
                }

                // PrintWriter writer = response.getWriter();

                response.setStatus(HttpServletResponse.SC_OK);

                ((Request) request).setHandled(true);
            }
        };
        server = new Server(0);
        // This fake WebDAV proxy receives Wagon's existing /site/.//path spelling.
        // Permit only that empty-segment fixture variation; site:run keeps Jetty defaults.
        server.getConnectors()[0]
                .getConnectionFactory(org.eclipse.jetty.server.HttpConnectionFactory.class)
                .getHttpConfiguration()
                .setUriCompliance(org.eclipse.jetty.http.UriCompliance.from("DEFAULT,AMBIGUOUS_EMPTY_SEGMENT"));

        ContextHandler context = new ContextHandler("/");
        context.setHandler(repoHandler);
        server.setHandler(context.get());
        server.start();
    }

    public SimpleDavServerHandler(Servlet servlet) throws Exception {
        siteTargetPath = null;
        server = new Server(0);
        // This fake WebDAV proxy receives Wagon's existing /site/.//path spelling.
        // Permit only that empty-segment fixture variation; site:run keeps Jetty defaults.
        server.getConnectors()[0]
                .getConnectionFactory(org.eclipse.jetty.server.HttpConnectionFactory.class)
                .getHttpConfiguration()
                .setUriCompliance(org.eclipse.jetty.http.UriCompliance.from("DEFAULT,AMBIGUOUS_EMPTY_SEGMENT"));

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        server.setHandler(context.get());
        context.addServlet(new ServletHolder(servlet), "/");

        server.start();
    }

    public int getPort() {
        return server.getURI().getPort();
    }

    public void stop() throws Exception {
        server.stop();
    }
}
