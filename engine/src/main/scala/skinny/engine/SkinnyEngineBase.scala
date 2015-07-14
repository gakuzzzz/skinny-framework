package skinny.engine

import scala.language.implicitConversions
import scala.language.reflectiveCalls

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }

import java.io.{ File, FileInputStream }
import javax.servlet.http.{ HttpServlet, HttpServletRequest }
import javax.servlet.{ ServletRegistration, Filter }

import skinny.engine.async.FutureSupport
import skinny.engine.base._
import skinny.engine.constant._
import skinny.engine.context.SkinnyEngineContext
import skinny.engine.control.{ PassException, HaltException }
import skinny.engine.implicits._
import skinny.engine.multipart.FileCharset
import skinny.engine.response.{ ResponseStatus, ActionResult, Found }
import skinny.engine.routing._
import skinny.engine.util.UriDecoder
import skinny.logging.LoggerProvider
import skinny.util.LoanPattern._

object SkinnyEngineBase {

  import ServletApiImplicits._
  import scala.collection.JavaConverters._

  /**
   * A key for request attribute that contains any exception
   * that might have occured before the handling has been
   * propagated to SkinnyEngineBase#handle (such as in
   * FileUploadSupport)
   */
  val PrehandleExceptionKey: String = "skinny.engine.PrehandleException"
  val HostNameKey: String = "skinny.engine.HostName"
  val PortKey: String = "skinny.engine.Port"
  val ForceHttpsKey: String = "skinny.engine.ForceHttps"

  private[this] val KeyPrefix: String = classOf[FutureSupport].getName
  val Callbacks: String = s"$KeyPrefix.callbacks"
  val RenderCallbacks: String = s"$KeyPrefix.renderCallbacks"
  val IsAsyncKey: String = s"$KeyPrefix.isAsync"

  def isAsyncResponse(implicit ctx: SkinnyEngineContext): Boolean = ctx.request.get(IsAsyncKey).exists(_ => true)

  def onSuccess(fn: Any => Unit)(implicit ctx: SkinnyEngineContext): Unit = addCallback(_.foreach(fn))

  def onFailure(fn: Throwable => Unit)(implicit ctx: SkinnyEngineContext): Unit = addCallback(_.failed.foreach(fn))

  def onCompleted(fn: Try[Any] => Unit)(implicit ctx: SkinnyEngineContext): Unit = addCallback(fn)

  def onRenderedSuccess(fn: Any => Unit)(implicit ctx: SkinnyEngineContext): Unit = addRenderCallback(_.foreach(fn))

  def onRenderedFailure(fn: Throwable => Unit)(implicit ctx: SkinnyEngineContext): Unit = addRenderCallback(_.failed.foreach(fn))

  def onRenderedCompleted(fn: Try[Any] => Unit)(implicit ctx: SkinnyEngineContext): Unit = addRenderCallback(fn)

  def callbacks(implicit ctx: SkinnyEngineContext): List[(Try[Any]) => Unit] =
    ctx.request.getOrElse(Callbacks, List.empty[Try[Any] => Unit]).asInstanceOf[List[Try[Any] => Unit]]

  def addCallback(callback: Try[Any] => Unit)(implicit ctx: SkinnyEngineContext): Unit = {
    ctx.request(Callbacks) = callback :: callbacks
  }

  def runCallbacks(data: Try[Any])(implicit ctx: SkinnyEngineContext): Unit = {
    callbacks.reverse foreach (_(data))
  }

  def renderCallbacks(implicit ctx: SkinnyEngineContext): List[(Try[Any]) => Unit] = {
    ctx.request.getOrElse(RenderCallbacks, List.empty[Try[Any] => Unit]).asInstanceOf[List[Try[Any] => Unit]]
  }

  def addRenderCallback(callback: Try[Any] => Unit)(implicit ctx: SkinnyEngineContext): Unit = {
    ctx.request(RenderCallbacks) = callback :: renderCallbacks
  }

  def runRenderCallbacks(data: Try[Any])(implicit ctx: SkinnyEngineContext): Unit = {
    renderCallbacks.reverse foreach (_(data))
  }

  def getServletRegistration(app: SkinnyEngineBase): Option[ServletRegistration] = {
    val registrations = app.servletContext.getServletRegistrations.values().asScala.toList
    registrations.find(_.getClassName == app.getClass.getName)
  }

}

/**
 * The base implementation of the SkinnyEngine DSL.
 * Intended to be portable to all supported backends.
 */
trait SkinnyEngineBase
    extends CoreHandler
    with CoreRoutingDsl
    with LoggerProvider
    with DynamicScope
    with Initializable
    with RouteRegistryAccessor
    with ErrorHandlerAccessor
    with ServletContextAccessor
    with EnvironmentAccessor
    with ParamsAccessor
    with RequestFormatAccessor
    with ResponseContentTypeAccessor
    with ResponseStatusAccessor
    with BeforeAfterDsl
    with UrlGenerator
    with ServletApiImplicits
    with RouteMatcherImplicits
    with CookiesImplicits
    with EngineParamsImplicits
    with DefaultImplicits
    with RicherStringImplicits
    with SessionImplicits {

  import SkinnyEngineBase._

  /**
   * true if async supported
   */
  protected def isAsyncExecutable(result: Any): Boolean = false

  /**
   * Executes routes in the context of the current request and response.
   *
   * $ 1. Executes each before filter with `runFilters`.
   * $ 2. Executes the routes in the route registry with `runRoutes` for
   * the request's method.
   * a. The result of runRoutes becomes the _action result_.
   * b. If no route matches the requested method, but matches are
   * found for other methods, then the `doMethodNotAllowed` hook is
   * run with each matching method.
   * c. If no route matches any method, then the `doNotFound` hook is
   * run, and its return value becomes the action result.
   * $ 3. If an exception is thrown during the before filters or the route
   * $    actions, then it is passed to the `errorHandler` function, and its
   * $    result becomes the action result.
   * $ 4. Executes the after filters with `runFilters`.
   * $ 5. The action result is passed to `renderResponse`.
   */
  protected def executeRoutes() {
    var result: Any = null
    var rendered = true

    def runActions = {
      val prehandleException = mainThreadRequest.get(SkinnyEngineBase.PrehandleExceptionKey)
      if (prehandleException.isEmpty) {
        val (rq, rs) = (mainThreadRequest, mainThreadResponse)
        SkinnyEngineBase.onCompleted { _ =>
          withRequestResponse(rq, rs) {
            val className = this.getClass.toString
            this match {
              case f: Filter if !rq.contains(s"skinny.engine.SkinnyEngineFilter.afterFilters.Run (${className})") =>
                rq(s"skinny.engine.SkinnyEngineFilter.afterFilters.Run (${className})") = new {}
                runFilters(routes.afterFilters)
              case f: HttpServlet if !rq.contains("skinny.engine.SkinnyEngineServlet.afterFilters.Run") =>
                rq("skinny.engine.SkinnyEngineServlet.afterFilters.Run") = new {}
                runFilters(routes.afterFilters)
              case _ =>
            }
          }
        }
        runFilters(routes.beforeFilters)
        val actionResult = runRoutes(routes(mainThreadRequest.requestMethod)).headOption
        // Give the status code handler a chance to override the actionResult
        val r = handleStatusCode(status) getOrElse {
          actionResult orElse matchOtherMethods() getOrElse doNotFound()
        }
        rendered = false
        r
      } else {
        throw prehandleException.get.asInstanceOf[Exception]
      }
    }

    cradleHalt(
      body = {
        result = runActions
      },
      errorHandler = { error =>
        {
          cradleHalt(
            body = {
              result = currentErrorHandler.apply(error)
              rendered = false
            },
            errorHandler =
              e => {
                SkinnyEngineBase.runCallbacks(Failure(e))
                try {
                  renderUncaughtException(e)(skinnyEngineContext)
                } finally {
                  SkinnyEngineBase.runRenderCallbacks(Failure(e))
                }
              }
          )
        }
      }
    )

    if (!rendered) renderResponse(result)
  }

  private[this] def cradleHalt(body: => Any, errorHandler: Throwable => Any): Any = {
    try body
    catch {
      case e: HaltException => {
        try {
          handleStatusCode(extractStatusCode(e)) match {
            case Some(result) => renderResponse(result)
            case _ => renderHaltException(e)
          }
        } catch {
          case e: HaltException => renderHaltException(e)
          case e: Throwable => errorHandler.apply(e)
        }
      }
      case e: Throwable => errorHandler.apply(e)
    }
  }

  protected def renderUncaughtException(e: Throwable)(implicit ctx: SkinnyEngineContext): Unit = {
    (status = 500)(ctx)
    if (isDevelopmentMode) {
      (contentType = "text/plain")(ctx)
      e.printStackTrace(ctx.response.getWriter)
    }
  }

  /**
   * Invokes each filters with `invoke`.
   * The results of the filters are discarded.
   */
  private[this] def runFilters(filters: Traversable[Route]): Unit = {
    for {
      route <- filters
      matchedRoute <- route(requestPath)
    } invoke(matchedRoute)
  }

  /**
   * Lazily invokes routes with `invoke`.
   * The results of the routes are returned as a stream.
   */
  protected def runRoutes(routes: Traversable[Route]): Stream[Any] = {
    for {
      route <- routes.toStream // toStream makes it lazy so we stop after match
      matchedRoute <- route.apply(requestPath)
      saved = saveMatchedRoute(matchedRoute)
      actionResult <- invoke(saved)
    } yield actionResult
  }

  private[this] def saveMatchedRoute(matchedRoute: MatchedRoute): MatchedRoute = {
    mainThreadRequest("skinny.engine.MatchedRoute") = matchedRoute
    setMultiparams(Some(matchedRoute), multiParams)
    matchedRoute
  }

  private[this] def matchedRoute(implicit ctx: SkinnyEngineContext): Option[MatchedRoute] = {
    ctx.request.get("skinny.engine.MatchedRoute").map(_.asInstanceOf[MatchedRoute])
  }

  /**
   * Invokes a route or filter.  The multiParams gathered from the route
   * matchers are merged into the existing route params, and then the action
   * is run.
   *
   * @param matchedRoute the matched route to execute
   *
   * @return the result of the matched route's action wrapped in `Some`,
   *         or `None` if the action calls `pass`.
   */
  protected def invoke(matchedRoute: MatchedRoute): Option[Any] = {
    withRouteMultiParams(Some(matchedRoute)) {
      liftAction(matchedRoute.action)
    }
  }

  private[this] def liftAction(action: Action): Option[Any] = {
    try {
      Some(action())
    } catch {
      case e: PassException => None
    }
  }

  /**
   * Called if no route matches the current request for any method.  The
   * default implementation varies between servlet and filter.
   */
  protected var doNotFound: Action

  def notFound(fun: => Any): Unit = {
    doNotFound = {
      () => fun
    }
  }

  /**
   * Called if no route matches the current request method, but routes
   * match for other methods.  By default, sends an HTTP status of 405
   * and an `Allow` header containing a comma-delimited list of the allowed
   * methods.
   */
  private[this] var doMethodNotAllowed: (Set[HttpMethod] => Any) = {
    allow =>
      status = 405
      mainThreadResponse.headers("Allow") = allow.mkString(", ")
  }

  def methodNotAllowed(f: Set[HttpMethod] => Any): Unit = {
    doMethodNotAllowed = f
  }

  private[this] def matchOtherMethods(): Option[Any] = {
    val allow = routes.matchingMethodsExcept(mainThreadRequest.requestMethod, requestPath)
    if (allow.isEmpty) None else liftAction(() => doMethodNotAllowed(allow))
  }

  private[this] def handleStatusCode(status: Int): Option[Any] = {
    for {
      handler <- routes(status)
      matchedHandler <- handler(requestPath)
      handlerResult <- invoke(matchedHandler)
    } yield handlerResult
  }

  protected def withRouteMultiParams[S](matchedRoute: Option[MatchedRoute])(thunk: => S): S = {
    val originalParams = multiParams
    setMultiparams(matchedRoute, originalParams)
    try {
      thunk
    } finally {
      mainThreadRequest(MultiParamsKey) = originalParams
    }
  }

  protected def setMultiparams[S](matchedRoute: Option[MatchedRoute], originalParams: MultiParams)(
    implicit ctx: SkinnyEngineContext): Unit = {
    val routeParams = matchedRoute.map(_.multiParams).getOrElse(Map.empty).map {
      case (key, values) =>
        key -> values.map(s => if (s.nonBlank) UriDecoder.secondStep(s) else s)
    }
    ctx.request(MultiParamsKey) = originalParams ++ routeParams
  }

  /**
   * Renders the action result to the response.
   * $ - If the content type is still null, call the contentTypeInferrer.
   * $ - Call the render pipeline on the result.
   */
  protected def renderResponse(actionResult: Any): Unit = {
    if (contentType == null) {
      contentTypeInferrer.lift(actionResult) foreach {
        contentType = _
      }
    }
    renderResponseBody(actionResult)
  }

  /**
   * A partial function to infer the content type from the action result.
   *
   * @return
   * $ - "text/plain" for String
   * $ - "application/octet-stream" for a byte array
   * $ - "text/html" for any other result
   */
  protected def contentTypeInferrer: ContentTypeInferrer = {
    case s: String => "text/plain"
    case bytes: Array[Byte] => MimeTypes(bytes)
    case is: java.io.InputStream => MimeTypes(is)
    case file: File => MimeTypes(file)
    case actionResult: ActionResult =>
      actionResult.headers.find {
        case (name, value) => name equalsIgnoreCase "CONTENT-TYPE"
      }.getOrElse(("Content-Type", contentTypeInferrer(actionResult.body)))._2
    //    case Unit | _: Unit => null
    case _ => "text/html"
  }

  /**
   * Renders the action result to the response body via the render pipeline.
   *
   * @see #renderPipeline
   */
  protected def renderResponseBody(actionResult: Any): Unit = {
    @tailrec def loop(ar: Any): Any = ar match {
      case _: Unit | Unit => runRenderCallbacks(Success(actionResult))
      case a => loop(renderPipeline.lift(a).getOrElse(()))
    }
    try {
      runCallbacks(Success(actionResult))
      loop(actionResult)
    } catch {
      case e: Throwable =>
        runCallbacks(Failure(e))
        try {
          renderUncaughtException(e)(skinnyEngineContext)
        } finally {
          runRenderCallbacks(Failure(e))
        }
    }
  }

  /**
   * The render pipeline is a partial function of Any => Any.  It is
   * called recursively until it returns ().  () indicates that the
   * response has been rendered.
   */
  protected def renderPipeline: RenderPipeline = {
    case 404 =>
      doNotFound()
    case ActionResult(status, x: Int, resultHeaders) =>
      mainThreadResponse.status = status
      resultHeaders foreach {
        case (name, value) => mainThreadResponse.addHeader(name, value)
      }
      mainThreadResponse.writer.print(x.toString)
    case status: Int =>
      mainThreadResponse.status = ResponseStatus(status)
    case bytes: Array[Byte] =>
      if (contentType != null && contentType.startsWith("text")) mainThreadResponse.setCharacterEncoding(FileCharset(bytes).name)
      mainThreadResponse.outputStream.write(bytes)
    case is: java.io.InputStream =>
      using(is) {
        util.io.copy(_, mainThreadResponse.outputStream)
      }
    case file: File =>
      if (contentType startsWith "text") mainThreadResponse.setCharacterEncoding(FileCharset(file).name)
      using(new FileInputStream(file)) {
        in => util.io.zeroCopy(in, mainThreadResponse.outputStream)
      }
    // If an action returns Unit, it assumes responsibility for the response
    case _: Unit | Unit | null =>
    // If an action returns Unit, it assumes responsibility for the response
    case ActionResult(ResponseStatus(404, _), _: Unit | Unit, _) => doNotFound()
    case actionResult: ActionResult =>
      mainThreadResponse.status = actionResult.status
      actionResult.headers.foreach {
        case (name, value) => mainThreadResponse.addHeader(name, value)
      }
      actionResult.body
    case x =>
      mainThreadResponse.writer.print(x.toString)
  }

  protected def renderHaltException(e: HaltException): Unit = {
    try {
      var rendered = false
      e match {
        case HaltException(Some(404), _, _, _: Unit | Unit) |
          HaltException(_, _, _, ActionResult(ResponseStatus(404, _), _: Unit | Unit, _)) =>
          renderResponse(doNotFound())
          rendered = true
        case HaltException(Some(status), Some(reason), _, _) =>
          mainThreadResponse.status = ResponseStatus(status, reason)
        case HaltException(Some(status), None, _, _) =>
          mainThreadResponse.status = ResponseStatus(status)
        case HaltException(None, _, _, _) => // leave status line alone
      }
      e.headers foreach {
        case (name, value) => mainThreadResponse.addHeader(name, value)
      }
      if (!rendered) renderResponse(e.body)
    } catch {
      case e: Throwable =>
        runCallbacks(Failure(e))
        renderUncaughtException(e)(skinnyEngineContext)
        runCallbacks(Failure(e))
    }
  }

  protected def extractStatusCode(e: HaltException): Int = e match {
    case HaltException(Some(status), _, _, _) => status
    case _ => mainThreadResponse.status.code
  }

  /**
   * Removes _all_ the actions of a given route for a given HTTP method.
   * If addRoute is overridden then this should probably be overriden too.
   *
   * @see skinny.engine.SkinnyEngineKernel#addRoute
   */
  protected def removeRoute(method: HttpMethod, route: Route): Unit = {
    routes.removeRoute(method, route)
  }

  protected def removeRoute(method: String, route: Route): Unit = {
    removeRoute(HttpMethod(method), route)
  }

  private[this] def addStatusRoute(codes: Range, action: => Any): Unit = {
    val route = Route(Seq.empty, () => action, (req: HttpServletRequest) => routeBasePath(skinnyEngineContext))
    routes.addStatusRoute(codes, route)
  }

  /**
   * Sends a redirect response and immediately halts the current action.
   */
  def redirect(uri: String)(implicit ctx: SkinnyEngineContext): Nothing = {
    halt(Found(fullUrl(uri, includeServletPath = false)(ctx)))
  }

  /**
   * The effective path against which routes are matched.  The definition
   * varies between servlets and filters.
   */
  def requestPath(implicit ctx: SkinnyEngineContext): String

}