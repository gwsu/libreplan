/*
 * This file is part of NavalPlan
 *
 * Copyright (C) 2011 Igalia, S.L.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.navalplanner.web.print;

import static org.zkoss.ganttz.i18n.I18nHelper._;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.navalplanner.business.orders.entities.Order;
import org.navalplanner.web.common.entrypoints.URLHandler;
import org.springframework.security.context.SecurityContext;
import org.springframework.security.context.SecurityContextHolder;
import org.zkoss.ganttz.Planner;
import org.zkoss.ganttz.servlets.CallbackServlet;
import org.zkoss.ganttz.servlets.CallbackServlet.IServletRequestHandler;
import org.zkoss.util.Locales;
import org.zkoss.zk.ui.Executions;

/**
 * Printing operation handler for wk2img utility based on WebKitGTK+.
 *
 * @author Manuel Rego Casasnovas <mrego@igalia.com>
 */
public class WKPrint {

    private static final Log LOG = LogFactory.getLog(WKPrint.class);

    /**
     * Utility command name
     */
    private static final String PRINT_COMMAND = "wk2img";

    /**
     * Estimated maximum execution time (ms). This have to be always greater
     * than CAPTURE_DELAY.
     */
    private static final int TIMEOUT = 30000;

    /**
     * Delay time (ms) for captures. Time needed to render page.
     */
    private static final int CAPTURE_DELAY = 10000;

    /**
     * Taskdetails left padding
     */
    private static int TASKDETAILS_BASE_WIDTH = 310;

    /**
     * Default width in pixels of the task name text field for depth level 1.
     *
     * Got from .listdetails .depth_1 input.task_title { width: 121px; } at
     * src/main/webapp/planner/css/ganttzk.css
     */
    private static final int BASE_TASK_NAME_PIXELS = 121;

    private static int TASK_HEIGHT = 25;
    private static int PRINT_VERTICAL_PADDING = 50;

    public static void print(Order order) {
        print("/planner/index.zul", entryPointForShowingOrder(order),
                Collections.<String, String> emptyMap());
    }

    public static void print(Order order, Map<String, String> parameters) {
        print("/planner/index.zul", entryPointForShowingOrder(order),
                parameters);
    }

    public static void print(Order order, HashMap<String, String> parameters,
            Planner planner) {
        print("/planner/index.zul", entryPointForShowingOrder(order),
                parameters, planner);
    }

    public static void print() {
        print("/planner/index.zul", Collections.<String, String> emptyMap(),
                Collections.<String, String> emptyMap());
    }

    public static void print(Map<String, String> parameters) {
        print("/planner/index.zul", Collections.<String, String> emptyMap(),
                parameters);
    }

    public static void print(HashMap<String, String> parameters, Planner planner) {
        print("/planner/index.zul", Collections.<String, String> emptyMap(),
                parameters, planner);
    }

    private static Map<String, String> entryPointForShowingOrder(Order order) {
        final Map<String, String> result = new HashMap<String, String>();
        result.put("order", order.getId() + "");
        return result;
    }

    public static void print(final String forwardURL,
            final Map<String, String> entryPointsMap,
            Map<String, String> parameters) {
        print(forwardURL, entryPointsMap, parameters, null);
    }

    public static void print(final String forwardURL,
            final Map<String, String> entryPointsMap,
            Map<String, String> parameters, Planner planner) {

        HttpServletRequest request = (HttpServletRequest) Executions
                .getCurrent().getNativeRequest();

        // Calculate application path and destination file relative route
        String absolutePath = request.getSession().getServletContext()
                .getRealPath("/");

        String filename = "/print/"
                + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + ".png";

        String url = CallbackServlet.registerAndCreateURLFor(request,
                executeOnOriginalContext(new IServletRequestHandler() {

                    @Override
                    public void handle(HttpServletRequest request,
                            HttpServletResponse response)
                            throws ServletException, IOException {

                        URLHandler.setupEntryPointsForThisRequest(request,
                                entryPointsMap);
                        // Pending to forward and process additional parameters
                        // as show labels, resources, zoom or expand all
                        request.getRequestDispatcher(forwardURL).forward(
                                request, response);
                    }
                }));

        // Generate capture string
        String captureString = PRINT_COMMAND;

        // Add capture destination callback URL
        String hostName = resolveLocalHost(request);
        captureString += " --url=http://" + hostName + ":"
                + request.getLocalPort() + url;

        if (parameters != null) {
            captureString += "?";
            for (String key : parameters.keySet()) {
                captureString += key + "=" + parameters.get(key) + "&";
            }
            captureString = captureString.substring(0,
                    (captureString.length() - (parameters.size() > 0 ? 1
                                    : 0)));
        }

        boolean expanded = Planner
                .guessContainersExpandedByDefaultGivenPrintParameters(parameters);
        int minWidthForTaskNameColumn = planner
                .calculateMinimumWidthForTaskNameColumn(expanded);
        int plannerWidth = calculatePlannerWidthForPrintingScreen(planner,
                minWidthForTaskNameColumn);
        // FIXME height have to be calculated
        captureString += " --height=1000";
        captureString += " --width=" + plannerWidth;
        captureString += " --delay=" + CAPTURE_DELAY;

        String generatedCSSFile = createCSSFile(absolutePath
                + "/planner/css/print.css", plannerWidth, planner, parameters
                .get("advances"), parameters.get("reportedHours"), parameters
                .get("labels"), parameters.get("resources"), expanded,
                minWidthForTaskNameColumn);

        // Relative user styles
        captureString += " --css=" + generatedCSSFile;

        // Destination complete absolute path
        captureString += " --output=" + absolutePath + filename;

        // Command execution
        try {
            LOG.info(captureString);

            // Ensure printing process finalization
            new Thread() {
                @Override
                public void run() {
                    try {
                        sleep(TIMEOUT);
                        LOG.info("Killing printing process if still alive");
                        Runtime.getRuntime().exec("killall " + PRINT_COMMAND);
                    } catch (Exception e) {
                        LOG.error(_("Timeout thread exception"), e);
                    }
                }
            }.start();

            Process printProcess = Runtime.getRuntime().exec(captureString);

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    printProcess.getInputStream()));
            String line = null;
            while ((line = in.readLine()) != null) {
                LOG.info("[Out] " + line);
            }
            BufferedReader err = new BufferedReader(new InputStreamReader(
                    printProcess.getErrorStream()));
            line = null;
            while ((line = err.readLine()) != null) {
                LOG.info("[Err] " + line);
            }

            printProcess.waitFor();
            printProcess.destroy();

        } catch (InterruptedException e) {
            LOG.error(_("Problems waiting for print command"), e);
        } catch (IOException e) {
            LOG.error(_("Could not execute print command"), e);
        }

        Executions.getCurrent().sendRedirect(filename, "_blank");
    }

    private static String resolveLocalHost(HttpServletRequest request) {
        try {
            InetAddress host = InetAddress.getByName(request.getLocalName());
            return host.getHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private static int calculatePlannerWidthForPrintingScreen(Planner planner,
            int minWidthForTaskNameColumn) {
        if (planner != null && planner.getTimeTracker() != null) {
            return planner.getTimeTracker().getHorizontalSize()
                    + calculateTaskDetailsWidth(minWidthForTaskNameColumn);
        }
        return 0;
    }

    private static int calculateTaskDetailsWidth(int minWidthForTaskNameColumn) {
        return TASKDETAILS_BASE_WIDTH
                + Math
                        .max(0, minWidthForTaskNameColumn
                                - BASE_TASK_NAME_PIXELS);
    }

    private static IServletRequestHandler executeOnOriginalContext(
            final IServletRequestHandler original) {
        final SecurityContext originalContext = SecurityContextHolder
                .getContext();
        final Locale current = Locales.getCurrent();
        return new IServletRequestHandler() {
            @Override
            public void handle(HttpServletRequest request,
                    HttpServletResponse response) throws ServletException,
                    IOException {
                Locales.setThreadLocal(current);
                SecurityContextHolder.setContext(originalContext);
                original.handle(request, response);
            }
        };
    }

    private static String heightCSS(int tasksNumber) {
        int height = (tasksNumber * TASK_HEIGHT) + PRINT_VERTICAL_PADDING;
        String heightCSS = "";
        heightCSS += " body div#scroll_container { height: " + height
                + "px !important;} \n"; /* 1110 */
        heightCSS += " body div#timetracker { height: " + (height + 20)
                + "px !important; } \n";
        heightCSS += " body div.plannerlayout { height: " + (height + 80)
                + "px !important; } \n";
        heightCSS += " body div.main-layout { height: " + (height + 90)
                + "px !important; } \n";
        return heightCSS;
    }

    private static String createCSSFile(String srFile, int width,
            Planner planner, String advances, String reportedHours,
            String labels, String resources, boolean expanded,
            int minimumWidthForTaskNameColumn) {
        File generatedCSS = null;
        try {
            generatedCSS = File.createTempFile("print", ".css");

            File f1 = new File(srFile);
            InputStream in = new FileInputStream(f1);

            // For Overwrite the file.
            OutputStream out = new FileOutputStream(generatedCSS);

            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            String includeCSSLines = " body { width: " + width + "px; } \n";
            if ((labels != null) && (labels.equals("all"))) {
                includeCSSLines += " .task-labels { display: inline !important;} \n";
            }
            if ((advances != null) && (advances.equals("all"))) {
                includeCSSLines += " .completion2 { display: inline !important;} \n";
            }
            if ((reportedHours != null) && (reportedHours.equals("all"))) {
                includeCSSLines += " .completion { display: inline !important;} \n";
            }

            if ((resources != null) && (resources.equals("all"))) {
                includeCSSLines += " .task-resources { display: inline !important;} \n";
            }
            includeCSSLines += heightCSS(expanded ? planner.getAllTasksNumber()
                    : planner.getTaskNumber());
            includeCSSLines += widthForTaskNamesColumnCSS(minimumWidthForTaskNameColumn);

            out.write(includeCSSLines.getBytes());
            in.close();
            out.close();

        } catch (FileNotFoundException ex) {
            LOG.error(ex.getMessage() + _(" in the specified directory."));
            System.exit(0);
        } catch (IOException e) {
            LOG.error(e.getMessage());
        }
        if (generatedCSS != null) {
            return generatedCSS.getAbsolutePath();
        } else {
            return srFile;
        }
    }

    private static String widthForTaskNamesColumnCSS(int minWidthPixels) {
        String css = "/* ------ Make the area for task names wider ------ */\n";
        css += "th.z-tree-col {width: 76px !important;}\n";
        css += "th.tree-text {width: " + (24 + minWidthPixels)
                + "px !important;}\n";
        css += ".taskdetailsContainer, .z-west-body, .z-tree-header, .z-tree-body {";
        css += "width: " + (176 + minWidthPixels) + "px !important;}\n";
        css += ".listdetails .depth_1 input.task_title {";
        css += "width: " + (minWidthPixels - 1) + "px !important;}\n";
        css += ".listdetails .depth_2 input.task_title {";
        css += "width: " + (minWidthPixels - 22) + "px !important;}\n";
        css += ".listdetails .depth_3 input.task_title {";
        css += "width: " + (minWidthPixels - 43) + "px !important;}\n";
        css += ".listdetails .depth_4 input.task_title {";
        css += "width: " + (minWidthPixels - 64) + "px !important;}\n";

        return css;
    }
}