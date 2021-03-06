package com.onelostlogician.aws.proxy;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.common.net.MediaType;
import com.onelostlogician.aws.proxy.ApiGatewayProxyResponse.ApiGatewayProxyResponseBuilder;
import org.apache.log4j.Logger;
import org.json.simple.JSONObject;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.Response.Status.*;

public abstract class LambdaProxyHandler<MethodHandlerConfiguration extends Configuration>
        implements RequestHandler<ApiGatewayProxyRequest, ApiGatewayProxyResponse> {
    private static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method".toLowerCase();
    private static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers".toLowerCase();
    private static final String ORIGIN_HEADER = "Origin".toLowerCase();
    private static final String MEDIA_TYPE_LIST_SEPARATOR = ",";
    private final Logger logger = Logger.getLogger(getClass());
    private final boolean corsSupport;
    private final Map<String, Function<MethodHandlerConfiguration, MethodHandler>> methodHandlerMap;

    public LambdaProxyHandler(boolean withCORSSupport) {
        this(withCORSSupport, new HashMap<>());
    }

    public LambdaProxyHandler(boolean withCORSSupport, Map<String, Function<MethodHandlerConfiguration, MethodHandler>> methodHandlerMap) {
        this.corsSupport = withCORSSupport;
        this.methodHandlerMap = keyValuesToLowerCase(methodHandlerMap);
    }

    public void registerMethodHandler(String method, Function<MethodHandlerConfiguration, MethodHandler> methodHandlerConstuctor) {
        methodHandlerMap.put(method.toLowerCase(), methodHandlerConstuctor);
    }

    @Override
    public ApiGatewayProxyResponse handleRequest(ApiGatewayProxyRequest request, Context context) {
        ApiGatewayProxyResponse response;
        try {
            MethodHandlerConfiguration configuration;
            try {
                configuration = getConfiguration(request, context);
            }
            catch (Exception e) {
                throw new LambdaException(getServerErrorResponse("This service is mis-configured. Please contact your system administrator.\n", e));
            }

            String method = request.getHttpMethod().toLowerCase();
            logger.info("Method: " + method + "\n");

            if (corsSupport && method.toLowerCase().equals("options")) {
                handleCORSRequest(request, configuration);
            }
            else if (!methodHandlerMap.keySet().contains(method)) {
                ApiGatewayProxyResponse wrongMethod =
                        new ApiGatewayProxyResponseBuilder()
                                .withStatusCode(BAD_REQUEST.getStatusCode())
                                .withBody(String.format("Lambda cannot handle the method %s", method))
                                .build();
                throw new LambdaException(wrongMethod);
            }
            MethodHandler methodHandler = getMethodHandler(configuration, method);
            Map<String, String> headers = keyValuesToLowerCase(request.getHeaders());

            String contentTypeHeader = CONTENT_TYPE.toLowerCase();
            validateHeaderOrThrow(headers, contentTypeHeader, UNSUPPORTED_MEDIA_TYPE);
            String acceptHeader = ACCEPT.toLowerCase();
            validateHeaderOrThrow(headers, acceptHeader, UNSUPPORTED_MEDIA_TYPE);

            List<MediaType> contentTypes;
            List<MediaType> acceptTypes;
            try {
                String contentTypeString = requireNonNull(headers.get(contentTypeHeader)).toLowerCase();
                contentTypes = getContentTypes(contentTypeString);
                String acceptString = requireNonNull(headers.get(acceptHeader)).toLowerCase();
                acceptTypes = getContentTypes(acceptString);
            }
            catch (IllegalArgumentException e) {
                ApiGatewayProxyResponse malformedMediaType =
                        new ApiGatewayProxyResponseBuilder()
                                .withStatusCode(BAD_REQUEST.getStatusCode())
                                .withBody(String.format("Malformed media type. %s", e.getMessage()))
                                .build();
                throw new LambdaException(malformedMediaType);
            }

            logger.info("Content-Type: " + contentTypes + "\n");
            logger.info("Accept: " + acceptTypes + "\n");

            response = methodHandler.handle(request, contentTypes, acceptTypes, context);
        }
        catch (Error e) {
            logger.error(request);
            response = new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                            .withBody(String.format("Failed to parse: %s", request))
                            .build();
        }
        catch(LambdaException e) {
            response = e.getResponse();
        }
        catch (Exception e) {
            logger.error(request);
            response = getServerErrorResponse("", e);
        }

        if (!request.getHttpMethod().toLowerCase().equals("options")) {
            Map<String, String> headers = response.getHeaders();
            headers.put("Access-Control-Allow-Origin", "*");
            response = response.builder()
                    .withHeaders(headers)
                    .build();
        }

        logger.info(String.format("Completed response: %s with size %s.\n", response.getStatusCode(), response.getBody().length()));
        return response;
    }

    private List<MediaType> getContentTypes(String contentTypeString) {
        return Stream.of(contentTypeString.split(MEDIA_TYPE_LIST_SEPARATOR))
                .filter(Objects::nonNull)
                .map(mediaType -> mediaType.replaceAll("\\s+",""))
                .map(MediaType::parse)
                .collect(toList());
    }

    private void handleCORSRequest(
            ApiGatewayProxyRequest request,
            MethodHandlerConfiguration configuration
    ) throws LambdaException {
        Map<String, String> headers = keyValuesToLowerCase(request.getHeaders());
        if (!headers.keySet().contains(ORIGIN_HEADER)) {
            ApiGatewayProxyResponse wrongHeaders = new ApiGatewayProxyResponseBuilder()
                    .withStatusCode(BAD_REQUEST.getStatusCode())
                    .withBody(String.format("Options method should include the %s header", ORIGIN_HEADER))
                    .build();
            throw new LambdaException(wrongHeaders);
        }
        if (!headers.keySet().contains(ACCESS_CONTROL_REQUEST_METHOD)) {
            ApiGatewayProxyResponse wrongHeaders = new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("Options method should include the %s header", ACCESS_CONTROL_REQUEST_METHOD))
                            .build();
            throw new LambdaException(wrongHeaders);
        }
        String methodBeingInvestigated = headers.get(ACCESS_CONTROL_REQUEST_METHOD).toLowerCase();
        if (!methodHandlerMap.keySet().contains(methodBeingInvestigated)) {
            ApiGatewayProxyResponse wrongMethod = new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("Lambda cannot handle the method %s", methodBeingInvestigated))
                            .build();
            throw new LambdaException(wrongMethod);
        }
        Collection<String> requiredHeaders = getMethodHandler(configuration, methodBeingInvestigated).getRequiredHeaders();
        String proposedRequestHeadersStr = headers.get(ACCESS_CONTROL_REQUEST_HEADERS);
        if (!requiredHeaders.isEmpty() && proposedRequestHeadersStr == null) {
            ApiGatewayProxyResponse wrongHeaders =
                    new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("The required header(s) not present: %s", ACCESS_CONTROL_REQUEST_HEADERS))
                            .build();
            throw new LambdaException(wrongHeaders);
        }
        List<String> proposedRequestHeaders = asList(proposedRequestHeadersStr
                    .replaceAll("\\s","")
                    .split(",")
                ).stream()
                .map(String::toLowerCase)
                .collect(toList());
        if (!proposedRequestHeaders.containsAll(requiredHeaders)) {
            ApiGatewayProxyResponse wrongHeaders =
                    new ApiGatewayProxyResponseBuilder()
                            .withStatusCode(BAD_REQUEST.getStatusCode())
                            .withBody(String.format("The required header(s) not present: %s", String.join(", ", requiredHeaders)))
                            .build();
            throw new LambdaException(wrongHeaders);
        }

        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Access-Control-Allow-Origin", headers.get(ORIGIN_HEADER));
        responseHeaders.put("Access-Control-Allow-Headers", proposedRequestHeaders.stream().collect(Collectors.joining(", ")));
        responseHeaders.put("Access-Control-Allow-Methods", headers.get(ACCESS_CONTROL_REQUEST_METHOD));
        ApiGatewayProxyResponse corsOk =
                new ApiGatewayProxyResponseBuilder()
                        .withStatusCode(OK.getStatusCode())
                        .withHeaders(responseHeaders)
                        .build();
        throw new LambdaException(corsOk);
    }

    private MethodHandler<?, ?> getMethodHandler(MethodHandlerConfiguration configuration, String method) {
        Function<MethodHandlerConfiguration, MethodHandler> methodHandlerConstructor = methodHandlerMap.get(method);
        return methodHandlerConstructor.apply(configuration);
    }

    private ApiGatewayProxyResponse getServerErrorResponse(String baseMessage, Exception e) {
        JSONObject body = new JSONObject();
        StringBuilder errorMessage = new StringBuilder();
        if (baseMessage != null && !baseMessage.isEmpty()) {
            errorMessage.append(baseMessage)
                        .append("\n");
        }
        errorMessage.append(e.getMessage());
        body.put("message", errorMessage.toString());
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        body.put("cause", sw.toString());
        return new ApiGatewayProxyResponseBuilder()
                .withStatusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .withBody(body.toJSONString())
                .build();
    }

    protected abstract MethodHandlerConfiguration getConfiguration(ApiGatewayProxyRequest request, Context context);

    private void validateHeaderOrThrow(Map<String, String> headers, String header, Status errorStatus) throws LambdaException {
        if (!headers.containsKey(header)) {
            ApiGatewayProxyResponse noHeaders = new ApiGatewayProxyResponseBuilder()
                    .withStatusCode(errorStatus.getStatusCode())
                    .withBody(String.format("No %s header", header))
                    .build();
            throw new LambdaException(noHeaders);
        }
    }

    private static <T> Map<String, T> keyValuesToLowerCase(Map<String, T> map) {
        return map.entrySet().stream()
                .collect(toMap(
                        entry -> entry.getKey().toLowerCase(),
                        Map.Entry::getValue
                ));
    }
}