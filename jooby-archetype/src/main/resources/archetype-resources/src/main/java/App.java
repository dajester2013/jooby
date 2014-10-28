package ${package};

import org.jooby.Jooby;

/**
 * @author jooby generator
 */
public class App extends Jooby {

  {
    assets("/assets/**");

    get("/", html("welcome.html"));
  }

  public static void main(final String[] args) throws Exception {
    new App().start();
  }

}