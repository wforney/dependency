package lib

import db.{InternalTasksDao, StaticUserProvider}
import io.flow.dependency.v0.models.{SyncType, TaskData, TaskDataSync, TaskDataSyncOne, TaskDataUndefinedType, TaskDataUpserted}
import io.flow.log.RollbarLogger
import io.flow.postgresql.OrderBy
import javax.inject.Inject
import sync.{BinarySync, LibrarySync, ProjectSync}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class TasksUtil @Inject() (
  rollbar: RollbarLogger,
  internalTasksDao: InternalTasksDao,
  binarySync: BinarySync,
  librarySync: LibrarySync,
  projectSync: ProjectSync,
  staticUserProvider: StaticUserProvider,
) {

  private[this] implicit val logger: RollbarLogger = rollbar.fingerprint(getClass.getName)

  /**
   * Invokes f, ensuring the task is marked processed once the function
   * returns. Logs any exceptions.
   */
  def process(taskId: String)(f: => Any)(implicit logger: RollbarLogger): Unit = {
    Try {
      f
    } match {
      case Success(_) => // no-op
      case Failure(ex) => logger.withKeyValue("task_id", taskId).warn("Error processing task", ex)
    }
    internalTasksDao.setProcessed(taskId)
  }

  /**
   * @param limit Max # of tasks to process on this iteration
   * @return Number of tasks processed
   */
  def process(limit: Long)(implicit ec: ExecutionContext): Long = {
    val all = internalTasksDao.findAll(
      hasProcessedAt = Some(false),
      limit = Some(limit),
      orderBy = OrderBy("priority, num_attempts, created_at")
    )
    all.foreach { t =>
      processData(t.id, t.data)
      internalTasksDao.setProcessed(t.id)
    }
    all.size.toLong
  }

  private[this] def processData(taskId: String, data: TaskData)(implicit ec: ExecutionContext): Unit = {
    data match {
        case _: TaskDataSync => {
          process(taskId) {
            binarySync.forall { r => internalTasksDao.queueBinary(r) }
            librarySync.forall { r => internalTasksDao.queueLibrary(r) }
            projectSync.forall { r => internalTasksDao.queueProject(r) }
          }
        }
        case data: TaskDataSyncOne => {
          data.`type` match {
            case SyncType.Binary => {
              process(taskId) {
                binarySync.sync(staticUserProvider.systemUser, data.id)
              }
            }
            case SyncType.Library => {
              process(taskId) {
                librarySync.sync(staticUserProvider.systemUser, data.id)
              }
            }
            case SyncType.Project => {
              process(taskId) {
                projectSync.sync(data.id)
              }
            }
            case SyncType.UNDEFINED(other) => {
              logger.withKeyValue("type", other).warn("SyncType.UNDEFINED - marking task processed")
            }
          }
        }
        case data: TaskDataUpserted => {
          data.`type` match {
            case SyncType.Binary => // no-op
            case SyncType.Library => // no-op
            case SyncType.Project => {
              process(taskId) {
                projectSync.upserted(data.id)
              }
            }
            case SyncType.UNDEFINED(other) => {
              logger.withKeyValue("type", other).warn("SyncType.UNDEFINED - marking task processed")
            }
          }
        }
        case TaskDataUndefinedType(other) => {
          logger.withKeyValue("type", other).warn("TaskDataUndefinedType")
        }
      }
  }

}