/*
 * This file is licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tresamigos.smv

import scala.util.Try

import java.io.{IOException, InputStreamReader, FileInputStream, File}
import java.util.Properties

import org.rogach.scallop.ScallopConf

/**
 * command line argument parsing using scallop library.
 * See (https://github.com/scallop/scallop) for details on using scallop.
 */
private[smv] class CmdLineArgsConf(args: Seq[String]) extends ScallopConf(args) {
  val DEFAULT_SMV_APP_CONF_FILE = "conf/smv-app-conf.props"
  val DEFAULT_SMV_USER_CONF_FILE = "conf/smv-user-conf.props"

  val smvProps = propsLong[String]("smv-props", "key=value command line props override")
  val smvAppDir = opt("smv-app-dir", noshort = true,
    default = Some("."),
    descr = "SMV app directory"
  )
  val smvAppConfFile = opt("smv-app-conf", noshort = true,
    default = Some(DEFAULT_SMV_APP_CONF_FILE),
    descr = "app level (static) SMV configuration file path")
  val smvUserConfFile = opt("smv-user-conf", noshort = true,
    default = Some(DEFAULT_SMV_USER_CONF_FILE),
    descr = "user level (dynamic) SMV configuration file path")
  val publish = opt[String]("publish", noshort = true,
    default = None,
    descr = "publish the given modules/stage/app as given version")
  val compareEdd = opt[List[String]]("edd-compare", noshort = true,
    default = None,
    descr = "compare two edd result files")
  val genEdd = toggle("edd", default = Some(false), noshort = true,
    descrYes = "summarize data and generate an edd file in the same directory as csv and schema",
    descrNo  = "do not summarize data")
  val graph = toggle("graph", default=Some(false),
    descrYes="generate a dependency graph of the given modules (modules are not run)",
    descrNo="do not generate a dependency graph")

  val json = opt[List[String]]("json", noshort = true, default = None, descr = "generate a json object to represent app's module dependency for a given set of stages (modules are not run)")

  val runConfObj = opt[String]("run-conf-obj", noshort = true, default = None, descr = "load and instantiate the configuration object by its fqn")

  // --- data directories override
  val dataDir    = opt[String]("data-dir",    noshort = true, descr = "specify the top level data directory")
  val inputDir   = opt[String]("input-dir",   noshort = true, descr = "specify the input directory (default: datadir/input")
  val outputDir  = opt[String]("output-dir",  noshort = true, descr = "specify the output directory (default: datadir/output")
  val publishDir = opt[String]("publish-dir", noshort = true, descr = "specify the publish directory (default: datadir/publish")

  val purgeOldOutput = toggle("purge-old-output", noshort = true, default = Some(false),
    descrYes = "remove all old output files in output dir ")
  val modsToRun = opt[List[String]]("run-module", 'm', descr = "run specified list of module FQNs")
  val stagesToRun = opt[List[String]]("run-stage", 's', descr = "run all output modules in specified stages")
  val runAllApp = toggle("run-app", noshort = true, default = Some(false),
                  descrYes = "run all output modules in all stages in app.")

  // if user specified "edd-compare" command line, user should have supplied two file names.
  validateOpt(compareEdd) {
    case (Some(edds)) if edds.length != 2 => Left("edd-compare param requires two EDD file names")
    case _ => Right(Unit)
  }

  // override default error message handler that does a sys.exit(1) which makes it difficult to debug.
  printedName = "SmvApp"
  errorMessageHandler = { errMsg =>
    println(printedName + ": " + errMsg)
    throw new IllegalArgumentException(errMsg)
  }

}

/**
 * Container of all SMV config driven elements (cmd line, app props, user props, etc).
 */
class SmvConfig(cmdLineArgs: Seq[String]) {
  import java.nio.file.Paths
  import SmvConfig._

  /*pathJoin has the following behavior:
   *pathJoin("/a/b", "c/d") -> "/a/b/c/d"
   *pathJoin("/a/b", "/c/d") -> "/c/d"
   */
  private def pathJoin(p1: String, p2: String) = Paths.get(p1).resolve(p2).toString

  val DEFAULT_SMV_HOME_CONF_FILE = sys.env.getOrElse("HOME", "") + "/.smv/smv-user-conf.props"

  // check for deprecated DATA_DIR environment variable.
  sys.env.get("DATA_DIR").foreach { d =>
    println("WARNING: use of DATA_DIR environment variable is deprecated. use smv.dataDir instead!!!")
  }

  val cmdLine = new CmdLineArgsConf(cmdLineArgs)

  private val appConfProps = _loadProps(pathJoin(cmdLine.smvAppDir(), cmdLine.smvAppConfFile()))
  private val usrConfProps = _loadProps(pathJoin(cmdLine.smvAppDir(), cmdLine.smvUserConfFile()))
  private val homeConfProps = _loadProps(DEFAULT_SMV_HOME_CONF_FILE)
  private val cmdLineProps = cmdLine.smvProps
  private val defaultProps = Map(
    "smv.appName" -> "Smv Application",
    "smv.stages" -> "",
    "smv.class_server.host" -> "",
    "smv.class_server.port" -> "9900",
    "smv.class_server.class_dir" -> "./target/classes"
  )

  // merge order is important here.  Highest priority comes last as it will override all previous
  private[smv] val mergedProps = defaultProps ++ appConfProps ++ homeConfProps ++ usrConfProps ++ cmdLineProps

  // --- config params.  App should access configs through vals below rather than from props maps
  val appName = mergedProps("smv.appName")
  val stageNames = splitProp("smv.stages")

  val classServerHost = mergedProps("smv.class_server.host")
  val classServerPort = getPropAsInt("smv.class_server.port").get
  val classServerClassDir = mergedProps("smv.class_server.class_dir")

  private val stagesList = stageNames map {SmvStage(_, this)}
  val stages = new SmvStages(stagesList.toSeq)

  val sparkSqlProps = mergedProps.filterKeys(k => k.startsWith("spark.sql."))

  /**
   * sequence of SmvModules to run based on the command line arguments.
   * Returns the union of -a/-m/-s command line flags.
   */
  private[smv] def modulesToRun() : Seq[SmvModule] = {

    val empty = Some(Seq.empty[String])
    val directMods = cmdLine.modsToRun.orElse(empty)().map {resolveModuleByName }
    val stageMods = cmdLine.stagesToRun.orElse(empty)().flatMap(s => stages.findStage(s).allOutputModules)
    val appMods = if (cmdLine.runAllApp()) stages.allOutputModules else Seq.empty[SmvModule]

    directMods ++ stageMods ++ appMods
  }

  def findModuleInStage(stage: String, name: String): Option[SmvModule] = {
    val candidates = (for {
      pkg <- stage.split('.').reverse.tails //  stage "a.b.c" -> ["c","b", "a"] ->
                                            //  [
                                            //    ["c", "b", "a"],
                                            //    ["b", "a"],
                                            //    ["a"],
                                            //    []
                                            //  ]
      candidate = (name +: pkg).reverse.mkString(".") //  "name" and ["c", "b", "a"] -> "a.b.c.name"
                                                      //  "name" and ["b", "a"] -> "a.b.name"

      // try each in turn as module object name
      // skip those that do not have an SmvModule defined
      m <- SmvReflection.findObjectByName[SmvModule](candidate).toOption
    }
    yield m).toSeq

    candidates.headOption
  }

  def resolveModuleByName(name: String): SmvModule = {
    val mods = for {
      stage <- stageNames.toSet[String]
      m <- findModuleInStage(stage, name)
    } yield m

    mods.size match {
      case 0 => throw new java.lang.IllegalArgumentException(s"""Cannot find module named [${name}] in any of the stages [${stageNames.mkString(", ")}]""")
      case 1 => mods.head
      case _ => throw new java.lang.RuntimeException(s"Module name [${name}] is not specific enough, as it is found in multiple stages [${stageNames.mkString(", ")}]")
    }
  }

  /** The FQN of configuration object for a particular run.  See github issue #319 */
  val runConfObj: Option[String] = cmdLine.runConfObj.get.orElse(mergedProps.get(RunConfObjKey))

  // ---------- hierarchy of data / input / output directories

  // TODO: need to remove requirement that data dir is specified (need to start using input dir and if both inpput/output are specified, we dont need datadir
  /** the configured data dir (command line OR props) */
  def dataDir : String = {
    cmdLine.dataDir.get.
      orElse(mergedProps.get("smv.dataDir")).
      orElse(sys.env.get("DATA_DIR")).
      getOrElse(throw new IllegalArgumentException("Must specify a data-dir either on command line or in conf."))
  }

  def inputDir : String = {
    cmdLine.inputDir.get.
      orElse(mergedProps.get("smv.inputDir")).
      getOrElse(dataDir + "/input")
  }

  def outputDir : String = {
    cmdLine.outputDir.get.
      orElse(mergedProps.get("smv.outputDir")).
      getOrElse(dataDir + "/output")
  }

  def publishDir : String = {
    cmdLine.publishDir.get.
      orElse(mergedProps.get("smv.publishDir")).
      getOrElse(dataDir + "/publish")
  }

  /**
   * load the given properties file and return the resulting props as a scala Map.
   * Note: if the provided file does not exit, this will just silently return an empty map.
   * This is by design as the default app/user smv config files may be missing (valid).
   * Adopted from the Spark AbstractCommnadBuilder::loadPropertiesFile
   */
  private def _loadProps(propsFileName: String) : scala.collection.Map[String, String] = {
    import scala.collection.JavaConverters._

    val props = new Properties()
    val propsFile = new File(propsFileName)

    if (propsFile.isFile()) {
      var fd: FileInputStream = null
      try {
        fd = new FileInputStream(propsFile)
        props.load(new InputStreamReader(fd, "UTF-8"));
      } finally {
        if (fd != null) {
          try {
            fd.close()
          } catch {
            case e:IOException => None
          }
        }
      }
    }

    props.asScala
  }

  /**
   * convert the property value to an array of strings.
   * Return empty array if original prop value was an empty string
   */
  private[smv] def splitProp(propName: String) = {
    val propVal = mergedProps(propName)
    if (propVal == "")
      Array[String]()
    else
      propVal.split(Array(',', ':')).map(_.trim)
  }

  /** Return the value of given configuration parameter as an optional string. */
  def getProp(propName: String) : Option[String] = {
    mergedProps.get(propName)
  }

  /** Get the given configuration property value as an integer option. */
  private[smv] def getPropAsInt(propName: String) : Option[Int] = {
    mergedProps.get(propName).flatMap { s: String => Try(s.toInt).toOption }
  }
}

object SmvConfig {
  val RunConfObjKey: String = "smv.runConfObj"
}
