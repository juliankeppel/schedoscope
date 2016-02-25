package org.schedoscope.scheduler.states

import java.util.Date
import org.schedoscope.scheduler.messages.MaterializeViewMode._
import org.schedoscope.dsl.transformations.Checksum.defaultDigest
import org.schedoscope.dsl.View
import org.schedoscope.Schedoscope

class NoOpIntermediateViewSchedulingStateMachine extends ViewSchedulingStateMachine {

  def materialize(
    currentState: ViewSchedulingState,
    listener: PartyInterestedInViewSchedulingStateChange,
    successFlagExists: => Boolean,
    materializationMode: MaterializeViewMode = DEFAULT,
    currentTime: Long = new Date().getTime) = currentState match {

    case CreatedByViewManager(view) => {
      ResultingViewSchedulingState(
        Waiting(view,
          defaultDigest,
          0,
          view.dependencies.toSet,
          Set(listener),
          materializationMode,
          false,
          false,
          false,
          0l),
        view.dependencies.map { Materialize(_, view, materializationMode) }.toSet)
    }

    case ReadFromSchemaManager(view, lastTransformationChecksum, lastTransformationTimestamp) => {
      ResultingViewSchedulingState(
        Waiting(view,
          lastTransformationChecksum,
          lastTransformationTimestamp,
          view.dependencies.toSet,
          Set(listener),
          materializationMode,
          false,
          false,
          false,
          0l),
        view.dependencies.map { Materialize(_, view, materializationMode) }.toSet)
    }

    case NoData(view) => {
      ResultingViewSchedulingState(
        Waiting(view,
          defaultDigest,
          0,
          view.dependencies.toSet,
          Set(listener),
          materializationMode,
          false,
          false,
          false,
          0l),
        view.dependencies.map { Materialize(_, view, materializationMode) }.toSet)
    }

    case Invalidated(view) => {
      ResultingViewSchedulingState(
        Waiting(view,
          defaultDigest,
          0,
          view.dependencies.toSet,
          Set(listener),
          materializationMode,
          false,
          false,
          false,
          0l),
        view.dependencies.map { Materialize(_, view, materializationMode) }.toSet)
    }

    case Waiting(
      view,
      lastTransformationChecksum,
      lastTransformationTimestamp,
      dependenciesMaterializing,
      listenersWaitingForMaterialize,
      materializationMode,
      oneDependencyReturnedData,
      withErrors,
      incomplete,
      dependenciesFreshness) => {
      ResultingViewSchedulingState(
        Waiting(
          view,
          lastTransformationChecksum,
          lastTransformationTimestamp,
          dependenciesMaterializing,
          listenersWaitingForMaterialize + listener,
          materializationMode,
          oneDependencyReturnedData,
          withErrors,
          incomplete,
          dependenciesFreshness), Set())
    }

    case Materialized(view, lastTransformationChecksum, lastTransformationTimestamp, _, _) => {
      ResultingViewSchedulingState(
        Waiting(view,
          lastTransformationChecksum,
          lastTransformationTimestamp,
          view.dependencies.toSet,
          Set(listener),
          materializationMode,
          false,
          false,
          false,
          0l),
        view.dependencies.map { Materialize(_, view, materializationMode) }.toSet)
    }
  }

  def invalidate(
    currentState: ViewSchedulingState,
    issuer: PartyInterestedInViewSchedulingStateChange) = currentState match {

    case waiting: Waiting => ResultingViewSchedulingState(
      waiting, Set(ReportNotInvalidated(waiting.view, Set(issuer))))

    case _ => ResultingViewSchedulingState(
      Invalidated(currentState.view),
      Set(
        ReportInvalidated(currentState.view, Set(issuer))))
  }

  private def leaveWaitingState(currentState: Waiting, setIncomplete: Boolean, setError: Boolean, successFlagExists: => Boolean, currentTime: Long) = currentState match {
    case Waiting(
      view,
      lastTransformationChecksum,
      lastTransformationTimestamp,
      dependenciesMaterializing,
      listenersWaitingForMaterialize,
      materializationMode,
      oneDependencyReturnedData,
      withErrors,
      incomplete,
      dependenciesFreshness) => {

      if (oneDependencyReturnedData && successFlagExists) {
        if (lastTransformationTimestamp < dependenciesFreshness || lastTransformationChecksum != view.transformation().checksum) {
          ResultingViewSchedulingState(
            Materialized(
              view,
              view.transformation().checksum,
              currentTime,
              withErrors | setError,
              incomplete | setIncomplete),
            {
              if (materializationMode == RESET_TRANSFORMATION_CHECKSUMS || lastTransformationChecksum != view.transformation().checksum)
                Set(WriteTransformationCheckum(view))
              else
                Set()
            } ++
              Set(
                WriteTransformationTimestamp(view, currentTime),
                ReportMaterialized(
                  view,
                  listenersWaitingForMaterialize,
                  currentTime,
                  withErrors | setError,
                  incomplete | setIncomplete)))
        } else
          ResultingViewSchedulingState(
            Materialized(
              view,
              lastTransformationChecksum,
              lastTransformationTimestamp,
              withErrors | setError,
              incomplete | setIncomplete),
            Set(
              ReportMaterialized(
                view,
                listenersWaitingForMaterialize,
                lastTransformationTimestamp,
                withErrors | setError,
                incomplete | setIncomplete))
              ++ {
                if (materializationMode == RESET_TRANSFORMATION_CHECKSUMS)
                  Set(WriteTransformationCheckum(view))
                else
                  Set()
              })
      } else
        ResultingViewSchedulingState(
          NoData(view),
          Set(
            ReportNoDataAvailable(view, listenersWaitingForMaterialize)))
    }
  }

  def noDataAvailable(currentState: Waiting, reportingDependency: View, successFlagExists: => Boolean, currentTime: Long = new Date().getTime) = currentState match {
    case Waiting(
      view,
      lastTransformationChecksum,
      lastTransformationTimestamp,
      dependenciesMaterializing,
      listenersWaitingForMaterialize,
      materializationMode,
      oneDependencyReturnedData,
      withErrors,
      incomplete,
      dependenciesFreshness) => {
      if (dependenciesMaterializing == Set(reportingDependency))
        leaveWaitingState(currentState, true, false, successFlagExists, currentTime)
      else
        ResultingViewSchedulingState(
          Waiting(
            view,
            lastTransformationChecksum,
            lastTransformationTimestamp,
            dependenciesMaterializing - reportingDependency,
            listenersWaitingForMaterialize,
            materializationMode,
            oneDependencyReturnedData,
            withErrors,
            true,
            dependenciesFreshness), Set())
    }
  }

  def failed(currentState: Waiting, reportingDependency: View, successFlagExists: => Boolean, currentTime: Long = new Date().getTime) = currentState match {
    case Waiting(
      view,
      lastTransformationChecksum,
      lastTransformationTimestamp,
      dependenciesMaterializing,
      listenersWaitingForMaterialize,
      materializationMode,
      oneDependencyReturnedData,
      withErrors,
      incomplete,
      dependenciesFreshness) => {
      if (dependenciesMaterializing == Set(reportingDependency))
        leaveWaitingState(currentState, true, true, successFlagExists, currentTime)
      else
        ResultingViewSchedulingState(
          Waiting(
            view,
            lastTransformationChecksum,
            lastTransformationTimestamp,
            dependenciesMaterializing - reportingDependency,
            listenersWaitingForMaterialize,
            materializationMode,
            oneDependencyReturnedData,
            true,
            true,
            dependenciesFreshness), Set())
    }
  }

  def materialized(currentState: Waiting, reportingDependency: View, transformationTimestamp: Long, successFlagExists: => Boolean, currentTime: Long = new Date().getTime) = currentState match {
    case Waiting(
      view,
      lastTransformationChecksum,
      lastTransformationTimestamp,
      dependenciesMaterializing,
      listenersWaitingForMaterialize,
      materializationMode,
      oneDependencyReturnedData,
      withErrors,
      incomplete,
      dependenciesFreshness) => {

      val updatedWaitingState = Waiting(
        view,
        lastTransformationChecksum,
        lastTransformationTimestamp,
        dependenciesMaterializing - reportingDependency,
        listenersWaitingForMaterialize,
        materializationMode,
        true,
        withErrors,
        incomplete,

        Math.max(dependenciesFreshness, transformationTimestamp))

      if (dependenciesMaterializing == Set(reportingDependency))
        leaveWaitingState(updatedWaitingState, false, false, successFlagExists, currentTime)
      else
        ResultingViewSchedulingState(updatedWaitingState, Set())
    }
  }

  def transformationSucceeded(currentState: Transforming, currentTime: Long = new Date().getTime) = ???

  def transformationFailed(currentState: Transforming, maxRetries: Int = Schedoscope.settings.retries, currentTime: Long = new Date().getTime) = ???

}