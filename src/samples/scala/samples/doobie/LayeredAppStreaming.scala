package samples.doobie

import io.github.gaelrenoux.tranzactio.doobie._
import io.github.gaelrenoux.tranzactio.{DbException, ErrorStrategiesRef}
import samples.{Conf, ConnectionPool, Person}
import zio._
import zio.stream._
import zio.Console

/** Same as LayeredApp, but using Doobie's stream (converted into ZIO strem). */
// scalastyle:off magic.number
object LayeredAppStreaming extends zio.ZIOAppDefault {

  private val zenv = ZEnv.any
  private val conf = Conf.live("samble-doobie-app-streaming")
  private val dbRecoveryConf = conf >>> ZLayer.fromService { (c: Conf.Root) => c.dbRecovery }
  private val datasource = (conf ++ zenv) >>> ConnectionPool.live
  private val database = (datasource ++ zenv ++ dbRecoveryConf) >>> Database.fromDatasourceAndErrorStrategies
  private val personQueries = PersonQueries.live

  type AppEnv = ZEnv with Database with PersonQueries with Conf
  private val appEnv = zenv ++ conf ++ database ++ personQueries

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] = {
    val prog = for {
      _ <- Console.printLine("Starting the app")
      trio <- myApp().provideLayer(appEnv)
      _ <- Console.printLine(trio.mkString(", "))
    } yield ExitCode(0)

    prog.orDie
  }

  /** Main code for the application. Results in a big ZIO depending on the AppEnv. */
  def myApp(): ZIO[AppEnv, DbException, List[Person]] = {
    val queries = for {
      _ <- Console.printLine("Creating the table").orDie
      _ <- PersonQueries.setup
      _ <- Console.printLine("Inserting the trio").orDie
      _ <- PersonQueries.insert(Person("Buffy", "Summers"))
      _ <- PersonQueries.insert(Person("Willow", "Rosenberg"))
      _ <- PersonQueries.insert(Person("Alexander", "Harris"))
      _ <- PersonQueries.insert(Person("Rupert", "Giles")) // insert one more!
      _ <- Console.printLine("Reading the trio").orDie
      trio <- {
        val stream: ZStream[PersonQueries with Connection, DbException, Person] = PersonQueries.listStream.take(3)
        stream.run(Sink.foldLeft(List[Person]()) { (ps, p) => p :: ps })
      }
    } yield trio.reverse

    ZIO.environmentWithZIO[AppEnv] { env =>
      // if this implicit is not provided, tranzactio will use Conf.Root.dbRecovery instead
      implicit val errorRecovery: ErrorStrategiesRef = env.get[Conf.Root].alternateDbRecovery
      Database.transactionOrWidenR(queries)
    }
  }

}
