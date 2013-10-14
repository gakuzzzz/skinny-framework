import _root_.controller._
import skinny._

class ScalatraBootstrap extends SkinnyLifeCycle {

  override def initSkinnyApp(ctx: ServletContext) {

    if (SkinnyEnv.isDevelopment() || SkinnyEnv.isTest()) {
      /* verbose query logger
      import scalikejdbc._
      GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings()
       */
      dev.DBInitializer.initialize()
    }

    ctx.mount(Controllers.root, "/*")
    ctx.mount(Controllers.programmers, "/*")
    ctx.mount(CompaniesController, "/*")
    ctx.mount(SkillsController, "/*")
  }

}
