package coverage.restassured;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * REST Assured filter that records one coverage record per HTTP request.
 *
 * <p>Writes a per-request coverage record ({@code "format":"openapi-coverage-v1"})
 * into the coverage output directory in the shape {@code CoverageComparator}
 * reads: {@code paths.<path>.<method>} with {@code parameters[]} (name, in and the
 * actual {@code value} for query/non-sensitive headers), a {@code requestBody}
 * presence marker, and a single {@code responses.<statusCode>} entry. The
 * coverage reporter later parses these files to build the report.
 *
 * <p>Values of sensitive headers (auth tokens, API keys, cookies) are intentionally
 * NOT recorded — only their names — to avoid leaking secrets into coverage artifacts.
 *
 * <p>Usage: register the filter on your REST Assured request spec (globally or
 * per request), pointing it at the directory the reporter reads:
 * <pre>{@code
 * RestAssured.filters(new OpenApiCoverageFilter(
 *     Path.of("build/coverage-output")));
 * }</pre>
 */
@Slf4j
public class OpenApiCoverageFilter implements Filter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Headers whose values must never be written to coverage files. */
  private static final Set<String> SENSITIVE_HEADERS = Set.of(
      "authorization", "x-api-key", "cookie", "proxy-authorization");

  private final Path outputDir;

  public OpenApiCoverageFilter(final Path outputDir) {
    this.outputDir = outputDir;
  }

  @Override
  public Response filter(final FilterableRequestSpecification requestSpec,
                         final FilterableResponseSpecification responseSpec,
                         final FilterContext ctx) {
    final Response response = ctx.next(requestSpec, responseSpec);
    try {
      writeCoverage(requestSpec, response);
    } catch (final Exception e) {
      log.warn("Failed to record coverage for {} {}: {}",
          requestSpec.getMethod(), requestSpec.getURI(), e.getMessage());
    }
    return response;
  }

  private void writeCoverage(final FilterableRequestSpecification requestSpec,
                             final Response response) throws Exception {
    final URI uri = new URI(requestSpec.getURI());

    final List<Map<String, Object>> parameters = new ArrayList<>();
    addQueryParams(parameters, uri.getRawQuery());
    for (final Header header : requestSpec.getHeaders()) {
      final boolean sensitive =
          SENSITIVE_HEADERS.contains(header.getName().toLowerCase(Locale.ROOT));
      parameters.add(parameter(header.getName(), "header",
          sensitive ? null : header.getValue()));
    }

    final Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("parameters", parameters);
    final Map<String, Object> requestBody = buildRequestBody(requestSpec);
    if (requestBody != null) {
      operation.put("requestBody", requestBody);
    }
    final List<String> mediaTypes = collectMediaTypes(requestSpec, response);
    if (!mediaTypes.isEmpty()) {
      operation.put("mediaTypes", mediaTypes);
    }
    operation.put("responses",
        Map.of(String.valueOf(response.statusCode()), Map.of()));

    final Map<String, Object> methods = new LinkedHashMap<>();
    methods.put(requestSpec.getMethod().toLowerCase(Locale.ROOT), operation);

    final Map<String, Object> paths = new LinkedHashMap<>();
    paths.put(uri.getPath(), methods);

    final Map<String, Object> document = new LinkedHashMap<>();
    document.put("format", "openapi-coverage-v1");
    document.put("paths", paths);

    Files.createDirectories(outputDir);
    final Path file = outputDir.resolve(UUID.randomUUID() + "-coverage.json");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
  }

  /**
   * Records whether a non-empty request body was sent, plus its content type.
   *
   * @param requestSpec the request being recorded
   * @return a request-body marker, or {@code null} when the request has no body
   */
  private static Map<String, Object> buildRequestBody(
      final FilterableRequestSpecification requestSpec) {
    final Object body = requestSpec.getBody();
    final boolean rawBody = body != null
        && !(body instanceof String && ((String) body).isBlank());
    // REST Assured keeps form-urlencoded and multipart payloads separately
    // from getBody(), so a form/multipart POST has a body even when getBody()
    // is null.
    final boolean formBody = requestSpec.getFormParams() != null
        && !requestSpec.getFormParams().isEmpty();
    final boolean multipartBody = requestSpec.getMultiPartParams() != null
        && !requestSpec.getMultiPartParams().isEmpty();
    if (!rawBody && !formBody && !multipartBody) {
      return null;
    }
    final Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("present", true);
    final String contentType = requestSpec.getHeaders().getValue("Content-Type");
    if (contentType != null) {
      requestBody.put("contentType", contentType);
    }
    return requestBody;
  }

  /**
   * Collects the media types exercised by this call — the request and response
   * {@code Content-Type}, normalized (parameters stripped, lower-cased) — to
   * match against the spec's declared content types.
   *
   * @param requestSpec the request being recorded
   * @param response    the received response
   * @return the distinct media types exercised, in encounter order
   */
  private static List<String> collectMediaTypes(
      final FilterableRequestSpecification requestSpec,
      final Response response) {
    final Set<String> types = new LinkedHashSet<>();
    addMediaType(types, requestSpec.getHeaders().getValue("Content-Type"));
    addMediaType(types, response.getContentType());
    return new ArrayList<>(types);
  }

  private static void addMediaType(final Set<String> out, final String raw) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    final int semicolon = raw.indexOf(';');
    final String mediaType =
        (semicolon >= 0 ? raw.substring(0, semicolon) : raw)
            .trim().toLowerCase(Locale.ROOT);
    if (!mediaType.isBlank()) {
      out.add(mediaType);
    }
  }

  private static void addQueryParams(final List<Map<String, Object>> parameters,
                                     final String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return;
    }
    for (final String pair : rawQuery.split("&")) {
      final int eq = pair.indexOf('=');
      final String name = decode(eq >= 0 ? pair.substring(0, eq) : pair);
      final String value = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
      if (!name.isBlank()) {
        parameters.add(parameter(name, "query", value));
      }
    }
  }

  private static String decode(final String raw) {
    return URLDecoder.decode(raw, StandardCharsets.UTF_8);
  }

  private static Map<String, Object> parameter(final String name,
                                               final String in,
                                               final String value) {
    final Map<String, Object> param = new LinkedHashMap<>();
    param.put("name", name);
    param.put("in", in);
    if (value != null) {
      param.put("value", value);
    }
    return param;
  }
}
