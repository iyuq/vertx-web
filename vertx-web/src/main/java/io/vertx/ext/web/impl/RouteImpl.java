/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.impl;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.net.impl.URIDecoder;
import io.vertx.ext.web.MIMEHeader;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is thread-safe
 * <p>
 * Some parts (e.g. content negotiation) from Yoke by Paulo Lopes
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 * @author <a href="http://pmlopes@gmail.com">Paulo Lopes</a>
 */
public class RouteImpl implements Route {

  private static final Logger log = LoggerFactory.getLogger(RouteImpl.class);

  private final RouterImpl router;
  private final Set<HttpMethod> methods = new HashSet<>();
  private final Set<MIMEHeader> consumes = new LinkedHashSet<>();
  private boolean emptyBodyPermittedWithConsumes = false;
  private final Set<MIMEHeader> produces = new LinkedHashSet<>();
  private String path;
  private int order;
  private boolean enabled = true;
  private List<Handler<RoutingContext>> contextHandlers;
  private List<Handler<RoutingContext>> failureHandlers;
  private boolean added;
  private Pattern pattern;
  private List<String> groups;
  private boolean useNormalisedPath = true;
  private Set<String> namedGroupsInRegex = new TreeSet<>();
  private Pattern virtualHostPattern;
  private boolean pathEndsWithSlash;

  private boolean exclusive;

  RouteImpl(RouterImpl router, int order) {
    this.router = router;
    this.order = order;
    this.contextHandlers = new ArrayList<>();
    this.failureHandlers = new ArrayList<>();
  }

  RouteImpl(RouterImpl router, int order, HttpMethod method, String path) {
    this(router, order);
    methods.add(method);
    checkPath(path);
    setPath(path);
  }

  RouteImpl(RouterImpl router, int order, String path) {
    this(router, order);
    checkPath(path);
    setPath(path);
  }

  RouteImpl(RouterImpl router, int order, HttpMethod method, String regex, boolean bregex) {
    this(router, order);
    methods.add(method);
    setRegex(regex);
  }

  RouteImpl(RouterImpl router, int order, String regex, boolean bregex) {
    this(router, order);
    setRegex(regex);
  }

  @Override
  public synchronized Route method(HttpMethod method) {
    methods.add(method);
    return this;
  }

  @Override
  public synchronized Route path(String path) {
    checkPath(path);
    setPath(path);
    return this;
  }

  @Override
  public synchronized Route pathRegex(String regex) {
    setRegex(regex);
    return this;
  }

  @Override
  public synchronized Route produces(String contentType) {
    ParsableMIMEValue value = new ParsableMIMEValue(contentType).forceParse();
    produces.add(value);
    return this;
  }

  @Override
  public synchronized Route consumes(String contentType) {
    ParsableMIMEValue value = new ParsableMIMEValue(contentType).forceParse();
    consumes.add(value);
    return this;
  }

  @Override
  public Route virtualHost(String hostnamePattern) {
    this.virtualHostPattern = Pattern.compile(hostnamePattern.replaceAll("\\.", "\\\\.").replaceAll("[*]", "(.*?)"), Pattern.CASE_INSENSITIVE);
    return this;
  }

  @Override
  public synchronized Route order(int order) {
    if (added) {
      throw new IllegalStateException("Can't change order after route is active");
    }
    this.order = order;
    return this;
  }

  @Override
  public synchronized Route last() {
    return order(Integer.MAX_VALUE);
  }

  @Override
  public synchronized Route handler(Handler<RoutingContext> contextHandler) {
    if (exclusive) {
      throw new IllegalStateException("This Route is exclusive for already mounted sub router.");
    }
    this.contextHandlers.add(contextHandler);
    checkAdd();
    return this;
  }

  @Override
  public Route blockingHandler(Handler<RoutingContext> contextHandler) {
    return blockingHandler(contextHandler, true);
  }

  @Override
  public Route subRouter(Router subRouter) {

    // The route path must end with a wild card
    if (exactPath) {
      throw new IllegalStateException("Sub router cannot be mounted on an exact path.");
    }
    // Parameters are allowed but full regex patterns not
    if (path == null && pattern != null) {
      throw new IllegalStateException("Sub router cannot be mounted on a regular expression path.");
    }
    // No other handler can be registered before or after this call (but they can on a new route object for the same path)
    if (contextHandlers.size() > 0 || failureHandlers.size() > 0) {
      throw new IllegalStateException("Only one sub router per Route object is allowed.");
    }

    handler(subRouter::handleContext);
    failureHandler(subRouter::handleFailure);

    subRouter.modifiedHandler(this::validateMount);

    // trigger a validation
    validateMount(subRouter);

    // mark the route as exclusive from now on
    exclusive = true;

    return this;
  }

  @Override
  public synchronized Route blockingHandler(Handler<RoutingContext> contextHandler, boolean ordered) {
    return handler(new BlockingHandlerDecorator(contextHandler, ordered));
  }

  @Override
  public synchronized Route failureHandler(Handler<RoutingContext> exceptionHandler) {
    if (exclusive) {
      throw new IllegalStateException("This Route is exclusive for already mounted sub router.");
    }
    this.failureHandlers.add(exceptionHandler);
    checkAdd();
    return this;
  }

  @Override
  public synchronized Route remove() {
    router.remove(this);
    return this;
  }

  @Override
  public synchronized Route disable() {
    enabled = false;
    return this;
  }

  @Override
  public synchronized Route enable() {
    enabled = true;
    return this;
  }

  @Override
  public Route useNormalisedPath(boolean useNormalisedPath) {
    this.useNormalisedPath = useNormalisedPath;
    return this;
  }

  @Override
  public String getPath() {
    return path;
  }

  @Override
  public boolean isRegexPath() {
    return pattern != null;
  }

  @Override
  public Set<HttpMethod> methods() {
    return this.methods;
  }

  @Override
  public Route setRegexGroupsNames(List<String> groups) {
    this.groups = groups;
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Route[ ");
    sb.append("path:").append(path);
    sb.append(" pattern:").append(pattern);
    sb.append(" handlers:").append(contextHandlers);
    sb.append(" failureHandlers:").append(failureHandlers);
    sb.append(" order:").append(order);
    sb.append(" methods:[");
    int cnt = 0;
    for (HttpMethod method : methods) {
      sb.append(method);
      cnt++;
      if (cnt < methods.size()) {
        sb.append(",");
      }
    }
    sb.append("]]@").append(System.identityHashCode(this));
    return sb.toString();
  }

  void handleContext(RoutingContextImplBase context) {
    Handler<RoutingContext> contextHandler;

    synchronized (this) {
      contextHandler = contextHandlers.get(context.currentRouteNextHandlerIndex() - 1);
    }

    contextHandler.handle(context);
  }

  void handleFailure(RoutingContextImplBase context) {
    Handler<RoutingContext> failureHandler;

    synchronized (this) {
      failureHandler = failureHandlers.get(context.currentRouteNextFailureHandlerIndex() - 1);
    }

    failureHandler.handle(context);
  }

  /**
   * @return 0 if route matches, otherwise it return the status code
   */
  synchronized int matches(RoutingContextImplBase context, String mountPoint, boolean failure) {

    if (failure && !hasNextFailureHandler(context) || !failure && !hasNextContextHandler(context)) {
      return 404;
    }
    if (!enabled) {
      return 404;
    }
    HttpServerRequest request = context.request();
    if (path != null && pattern == null && !pathMatches(mountPoint, context)) {
      return 404;
    }
    if (pattern != null) {
      String path = useNormalisedPath ? context.normalisedPath() : context.request().path();
      if (mountPoint != null) {
        path = path.substring(mountPoint.length());
      }

      Matcher m = pattern.matcher(path);
      if (m.matches()) {

        if (!methods.isEmpty() && !methods.contains(request.method())) {
          // If I'm here path or path pattern matches, but the method is wrong
          return 405;
        }

        context.matchRest = -1;
        context.matchNormalized = useNormalisedPath;

        if (m.groupCount() > 0) {
          if (!exactPath) {
            context.matchRest = m.start("rest");
          }

          if (groups != null) {
            // Pattern - named params
            // decode the path as it could contain escaped chars.
            for (int i = 0; i < Math.min(groups.size(), m.groupCount()); i++) {
              final String k = groups.get(i);
              String undecodedValue;
              // We try to take value in three ways:
              // 1. group name of type p0, p1, pN (most frequent and used by vertx params)
              // 2. group name inside the regex
              // 3. No group name
              try {
                undecodedValue = m.group("p" + i);
              } catch (IllegalArgumentException e) {
                try {
                  undecodedValue = m.group(k);
                } catch (IllegalArgumentException e1) {
                  // Groups starts from 1 (0 group is total match)
                  undecodedValue = m.group(i + 1);
                }
              }
              addPathParam(context, k, undecodedValue);
            }
          } else {
            // Straight regex - un-named params
            // decode the path as it could contain escaped chars.
            for (String namedGroup : namedGroupsInRegex) {
              String namedGroupValue = m.group(namedGroup);
              if (namedGroupValue != null) {
                addPathParam(context, namedGroup, namedGroupValue);
              }
            }
            for (int i = 0; i < m.groupCount(); i++) {
              String group = m.group(i + 1);
              if (group != null) {
                final String k = "param" + i;
                addPathParam(context, k, group);
              }
            }
          }
        }
      } else {
        return 404;
      }
    } else {
      // no pattern check for wrong method
      if (!methods.isEmpty() && !methods.contains(request.method())) {
        // If I'm here path or path pattern matches, but the method is wrong
        return 405;
      }
    }

    if (!consumes.isEmpty()) {
      // Can this route consume the specified content type
      MIMEHeader contentType = context.parsedHeaders().contentType();
      MIMEHeader consumal = contentType.findMatchedBy(consumes);
      if (consumal == null && !(contentType.rawValue().isEmpty() && emptyBodyPermittedWithConsumes)) {
        if (contentType.rawValue().isEmpty()) {
          return 400;
        } else {
          return 415;
        }
      }
    }
    List<MIMEHeader> acceptableTypes = context.parsedHeaders().accept();
    if (!produces.isEmpty() && !acceptableTypes.isEmpty()) {
      MIMEHeader selectedAccept = context.parsedHeaders().findBestUserAcceptedIn(acceptableTypes, produces);
      if (selectedAccept != null) {
        context.setAcceptableContentType(selectedAccept.rawValue());
        return 0;
      }
      return 406;
    }
    if (!virtualHostMatches(context.request.host())) return 404;
    return 0;
  }

  private void addPathParam(RoutingContext context, String name, String value) {
    HttpServerRequest request = context.request();
    final String decodedValue = URIDecoder.decodeURIComponent(value, false);
    if (!request.params().contains(name)) {
      request.params().add(name, decodedValue);
    }
    context.pathParams().put(name, decodedValue);
  }

  RouterImpl router() {
    return router;
  }

  private boolean pathMatches(String mountPoint, RoutingContext ctx) {
    String thePath = mountPoint == null ? path : mountPoint + path;
    String requestPath;

    if (useNormalisedPath) {
      // never null
      requestPath = ctx.normalisedPath();
    } else {
      requestPath = ctx.request().path();
      // can be null
      if (requestPath == null) {
        requestPath = "/";
      }
    }

    if (exactPath) {
      return pathMatchesExact(requestPath, thePath);
    } else {
      if (pathEndsWithSlash && (requestPath.charAt(requestPath.length() - 1) == '/'
          ? requestPath.equals(thePath) : thePath.regionMatches(0, requestPath, 0, requestPath.length()))) {
        return true;
      }
      return requestPath.startsWith(thePath);
    }
  }

  private boolean virtualHostMatches(String host) {
    if (virtualHostPattern == null) return true;
    boolean match = false;
    for (String h : host.split(":")) {
      if (virtualHostPattern.matcher(h).matches()) {
        match = true;
        break;
      }
    }
    return match;
  }

  private boolean pathMatchesExact(String path1, String path2) {
    // Ignore trailing slash when matching paths
    final int idx1 = path1.length() - 1;
    return pathEndsWithSlash ?
       (path1.charAt(idx1) == '/' ? path1.equals(path2) : path2.regionMatches(0, path1, 0, path1.length()))
      :(path1.charAt(idx1) != '/' ? path1.equals(path2) : path1.regionMatches(0, path2, 0, path2.length()));
  }


  private void setPath(String path) {
    // See if the path contains ":" - if so then it contains parameter capture groups and we have to generate
    // a regex for that
    if (path.charAt(path.length() - 1) != '*') {
      exactPath = true;
      this.path = path;
    } else {
      exactPath = false;
      this.path = path.substring(0, path.length() - 1);
    }

    if (path.indexOf(':') != -1) {
      createPatternRegex(path);
    }

    pathEndsWithSlash = this.path.endsWith("/");
  }

  private void setRegex(String regex) {
    pattern = Pattern.compile(regex);
    exactPath = true;
    Set<String> namedGroups = findNamedGroups(pattern.pattern());
    if (!namedGroups.isEmpty()) {
      namedGroupsInRegex.addAll(namedGroups);
    }
  }

  private Set<String> findNamedGroups(String path) {
    Set<String> namedGroups = new TreeSet<>();
    Matcher m = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(path);

    while (m.find()) {
      namedGroups.add(m.group(1));
    }
    return namedGroups;
  }

  // intersection of regex chars and https://tools.ietf.org/html/rfc3986#section-3.3
  private static final Pattern RE_OPERATORS_NO_STAR = Pattern.compile("([\\(\\)\\$\\+\\.])");

  // Pattern for :<token name> in path
  private static final Pattern RE_TOKEN_SEARCH = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)");

  private void createPatternRegex(String path) {
    // escape path from any regex special chars
    path = RE_OPERATORS_NO_STAR.matcher(path).replaceAll("\\\\$1");
    // allow usage of * at the end as per documentation
    if (path.charAt(path.length() - 1) == '*') {
      path = path.substring(0, path.length() - 1) + "(?<rest>.*)";
      exactPath = false;
    } else {
      exactPath = true;
    }

    // We need to search for any :<token name> tokens in the String and replace them with named capture groups
    Matcher m = RE_TOKEN_SEARCH.matcher(path);
    StringBuffer sb = new StringBuffer();
    groups = new ArrayList<>();
    int index = 0;
    while (m.find()) {
      String param = "p" + index;
      String group = m.group().substring(1);
      if (groups.contains(group)) {
        throw new IllegalArgumentException("Cannot use identifier " + group + " more than once in pattern string");
      }
      m.appendReplacement(sb, "(?<" + param + ">[^/]+)");
      groups.add(group);
      index++;
    }
    m.appendTail(sb);
    path = sb.toString();
    pattern = Pattern.compile(path);
  }

  private void checkPath(String path) {
    if ("".equals(path) || path.charAt(0) != '/') {
      throw new IllegalArgumentException("Path must start with /");
    }
  }

  private boolean exactPath;

  int order() {
    return order;
  }

  private void checkAdd() {
    if (!added) {
      router.add(this);
      added = true;
    }
  }

  synchronized protected boolean hasNextContextHandler(RoutingContextImplBase context) {
    return context.currentRouteNextHandlerIndex() < contextHandlers.size();
  }

  synchronized protected boolean hasNextFailureHandler(RoutingContextImplBase context) {
    return context.currentRouteNextFailureHandlerIndex() < failureHandlers.size();
  }

  public void setEmptyBodyPermittedWithConsumes(boolean emptyBodyPermittedWithConsumes) {
    this.emptyBodyPermittedWithConsumes = emptyBodyPermittedWithConsumes;
  }

  private void validateMount(Router router) {
    for (Route route : router.getRoutes()) {
      final String combinedPath;

      // this method is similar to what the pattern generation does but
      // it will not generate a pattern, it will only verify if the paths do not contain
      // colliding parameter names with the mount path

      // escape path from any regex special chars
      combinedPath = RE_OPERATORS_NO_STAR
        .matcher(path + (pathEndsWithSlash ? route.getPath().substring(1) : route.getPath()))
        .replaceAll("\\\\$1");

      // We need to search for any :<token name> tokens in the String
      Matcher m = RE_TOKEN_SEARCH.matcher(combinedPath);
      Set<String> groups = new HashSet<>();
      while (m.find()) {
        String group = m.group();
        if (groups.contains(group)) {
          throw new IllegalStateException("Cannot use identifier " + group + " more than once in pattern string");
        }
        groups.add(group);
      }
    }
  }
}
