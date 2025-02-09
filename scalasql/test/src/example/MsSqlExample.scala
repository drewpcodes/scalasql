package scalasql.example

import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.output.WaitingConsumer
import org.testcontainers.containers.output.OutputFrame.OutputType.STDOUT
import org.testcontainers.containers.wait.strategy.Wait
import scalasql.Table
import scalasql.MsSqlDialect.*

import java.util.concurrent.TimeUnit
import java.util.logging.{Level, Logger}

object MsSqlExample {
  case class ExampleProduct[T[_]](
      id: T[Int],
      kebabCaseName: T[String],
      name: T[String],
      price: T[Double]
  )

  object ExampleProduct extends Table[ExampleProduct]

  // NB MS JDBC Driver checks connection in loop and spams logs before container is up
  val connectionLogger = Logger
    .getLogger("com.microsoft.sqlserver.jdbc.internals.SQLServerConnection")
  connectionLogger.setLevel(Level.SEVERE)

  lazy val mssql = {
    println("Initializing MsSql")
    val mssql = new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU17-ubuntu-22.04")
    mssql.acceptLicense()
    // TODO Ensure it's only the LC ENV setting encoding on the driver connection to UTF8, we cannot force the use of UTF8 encoding in the database
    mssql.addEnv("MSSQL_COLLATION", "Latin1_General_100_BIN2_UTF8")
    mssql.waitingFor(Wait.forLogMessage(".*The tempdb database has.*", 1))
    mssql.start()
    mssql
  }

  val dataSource = new com.microsoft.sqlserver.jdbc.SQLServerDataSource
  dataSource.setURL(mssql.getJdbcUrl)
  dataSource.setUser(mssql.getUsername)
  dataSource.setPassword(mssql.getPassword)

  lazy val mssqlClient = new scalasql.DbClient.DataSource(
    dataSource,
    config = new scalasql.Config {}
  )

  def main(args: Array[String]): Unit = {
    mssqlClient.transaction { db =>
      db.updateRaw("""
      CREATE TABLE example_product (
          id INT PRIMARY KEY IDENTITY(1, 1),
          kebab_case_name NVARCHAR(256),
          name NVARCHAR(256),
          price DECIMAL(20, 2)
      );
      """)

      val inserted = db.run(
        ExampleProduct.insert.batched(_.kebabCaseName, _.name, _.price)(
          ("face-mask", "Face Mask", 8.88),
          ("guitar", "Guitar", 300),
          ("socks", "Socks", 3.14),
          ("skate-board", "Skate Board", 123.45),
          ("camera", "\uD83D\uDCF8", 1000.00),
          ("cookie", "Cookie", 0.10)
        )
      )

      assert(inserted == 6)

      val result =
        db.run(ExampleProduct.select.filter(_.price > 10).sortBy(_.price).desc.map(_.name))

      assert(result == Seq("\uD83D\uDCF8", "Guitar", "Skate Board"))

      db.run(ExampleProduct.update(_.name === "Cookie").set(_.price := 11.0))

      db.run(ExampleProduct.delete(_.name === "Guitar"))

      val result2 =
        db.run(ExampleProduct.select.filter(_.price > 10).sortBy(_.price).desc.map(_.name))

      assert(result2 == Seq("\uD83D\uDCF8", "Skate Board", "Cookie"))
    }
  }
}
