/*
 * Copyright 2018 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.servlet.response;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.linkedin.kafka.cruisecontrol.KafkaCruiseControl;
import com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;


/**
 * The util class for Kafka Cruise Control response.
 */
public class ResponseUtils {
  public static final int JSON_VERSION = 1;
  public static final String VERSION = "version";
  public static final String MESSAGE = "message";
  private static final String STACK_TRACE = "stackTrace";
  private static final String ERROR_MESSAGE = "errorMessage";

  private ResponseUtils() {
  }

  static void setResponseCode(HttpServletResponse response, int code, boolean json, KafkaCruiseControlConfig config) {
    response.setStatus(code);
    response.setContentType(json ? "application/json" : "text/plain");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    boolean corsEnabled = config == null ? false : config.getBoolean(KafkaCruiseControlConfig.WEBSERVER_HTTP_CORS_ENABLED_CONFIG);
    if (corsEnabled) {
      // These headers are exposed to the browser
      response.setHeader("Access-Control-Allow-Origin",
                         config.getString(KafkaCruiseControlConfig.WEBSERVER_HTTP_CORS_ORIGIN_CONFIG));
      response.setHeader("Access-Control-Expose-Headers",
                         config.getString(KafkaCruiseControlConfig.WEBSERVER_HTTP_CORS_EXPOSEHEADERS_CONFIG));
      response.setHeader("Access-Control-Allow-Credentials", "true");
    }
  }

  static String getBaseJSONString(String message) {
    Map<String, Object> jsonResponse = new HashMap<>(2);
    jsonResponse.put(VERSION, JSON_VERSION);
    jsonResponse.put(MESSAGE, message);
    return new Gson().toJson(jsonResponse);
  }

  static void writeResponseToOutputStream(HttpServletResponse response,
                                          int responseCode,
                                          boolean json,
                                          boolean wantJsonSchema,
                                          String responseMessage,
                                          KafkaCruiseControlConfig config)
      throws IOException {
    OutputStream out = response.getOutputStream();
    setResponseCode(response, responseCode, json, config);
    response.addHeader("Cruise-Control-Version", KafkaCruiseControl.cruiseControlVersion());
    response.addHeader("Cruise-Control-Commit_Id", KafkaCruiseControl.cruiseControlCommitId());
    if (json && wantJsonSchema) {
      response.addHeader("Cruise-Control-JSON-Schema", getJsonSchema(responseMessage));
    }
    response.setContentLength(responseMessage.length());
    out.write(responseMessage.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /**
   * Retrieve stack trace (if any).
   *
   * @param e Exception from which the stack trace will be retrieved.
   * @return Stack trace if the given exception is not {@code null}, empty string otherwise.
   */
  private static String stackTrace(Exception e) {
    if (e == null) {
      return "";
    }

    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  /**
   * Write error response to the output stream.
   *
   * @param response HTTP response to return to user.
   * @param e Exception (if any) corresponding to the error, {@code null} otherwise.
   * @param errorMessage Error message to return in the response message.
   * @param responseCode HTTP Status code to indicate the error.
   * @param json True if json, false otherwise.
   * @param config The configurations for Cruise Control.
   */
  public static void writeErrorResponse(HttpServletResponse response,
                                        Exception e,
                                        String errorMessage,
                                        int responseCode,
                                        boolean json,
                                        boolean wantJsonSchema,
                                        KafkaCruiseControlConfig config)
      throws IOException {
    String responseMessage;
    if (json) {
      Map<String, Object> exceptionMap = new HashMap<>();
      exceptionMap.put(VERSION, JSON_VERSION);
      exceptionMap.put(STACK_TRACE, stackTrace(e));
      exceptionMap.put(ERROR_MESSAGE, errorMessage);
      Gson gson = new Gson();
      responseMessage = gson.toJson(exceptionMap);
    } else {
      responseMessage = errorMessage == null ? "" : errorMessage;
    }
    // Send the CORS Task ID header as part of this error response if 2-step verification is enabled.
    writeResponseToOutputStream(response, responseCode, json, wantJsonSchema, responseMessage, config);
  }

  private static String getJsonSchema(String responseMessage) {
    JsonElement response = new JsonParser().parse(responseMessage);
    return convertNodeToStringSchemaNode(response, null);
  }

  private static String convertNodeToStringSchemaNode(JsonElement node, String key) {
    StringBuilder result = new StringBuilder();

    if (key != null) {
      result.append("\"" + key + "\": { \"type\": \"");
    } else {
      result.append("{ \"type\": \"");
    }
    if (node.isJsonArray()) {
      result.append("array\"");
      JsonArray arr = node.getAsJsonArray();
      if (arr.size() > 0) {
        result.append(", \"items\": [");
        // Generate schema based on the first item of the array, since the schema should be consistent between elements in the array.
        result.append(convertNodeToStringSchemaNode(arr.get(0), null));
        result.append("]");
      }
      result.append("}");
    } else if (node.isJsonPrimitive()) {
      if (node.getAsJsonPrimitive().isBoolean()) {
        result.append("boolean\" }");
      } else if (node.getAsJsonPrimitive().isNumber()) {
        result.append("number\" }");
      } else if (node.getAsJsonPrimitive().isString()) {
        result.append("string\" }");
      }
    } else if (node.isJsonObject()) {
      result.append("object\", \"properties\": ");
      result.append("{");
      for (Iterator<Map.Entry<String, JsonElement>> iterator = node.getAsJsonObject().entrySet().iterator(); iterator.hasNext(); ) {
        Map.Entry<String, JsonElement> entry = iterator.next();
        key = entry.getKey();
        JsonElement child = entry.getValue();

        result.append(convertNodeToStringSchemaNode(child, key));
        if (iterator.hasNext()) {
          result.append(",");
        }
      }
      result.append("}}");
    } else if (node.isJsonNull()) {
      result.append("}");
    }

    return result.toString();
  }
}
