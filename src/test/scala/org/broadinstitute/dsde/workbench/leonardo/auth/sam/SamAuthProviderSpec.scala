package org.broadinstitute.dsde.workbench.leonardo.auth.sam

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.swagger.client.ApiException
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDataprocDAO, MockGoogleIamDAO}
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData
import org.broadinstitute.dsde.workbench.leonardo.auth.sam.SamAuthProvider.NotebookAuthCacheKey
import org.broadinstitute.dsde.workbench.leonardo.db.TestComponent
import org.broadinstitute.dsde.workbench.leonardo.model.NotebookClusterActions.{DeleteCluster, SyncDataToCluster}
import org.broadinstitute.dsde.workbench.leonardo.model.ProjectActions.CreateClusters
import org.broadinstitute.dsde.workbench.leonardo.model._
import org.broadinstitute.dsde.workbench.leonardo.model.google._
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.mockito.ArgumentMatchers.{eq => mockitoEq, any}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext

class TestSamAuthProvider(authConfig: Config, serviceAccountProvider: ServiceAccountProvider) extends SamAuthProvider(authConfig, serviceAccountProvider)  {
  override lazy val samClient = new MockSwaggerSamClient()
}

class SamAuthProviderSpec extends TestKit(ActorSystem("leonardotest")) with FreeSpecLike with Matchers with TestComponent with CommonTestData with ScalaFutures with BeforeAndAfterAll with MockitoSugar with LazyLogging {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private def getSamAuthProvider: TestSamAuthProvider = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"),serviceAccountProvider)

  val gdDAO = new MockGoogleDataprocDAO
  val iamDAO = new MockGoogleIamDAO

  "should add and delete a notebook-cluster resource with correct actions for the user when a cluster is created and then destroyed" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    samAuthProvider.samClient.billingProjects += (project, userInfo.userEmail) -> Set("launch_notebook_cluster")
    // check the sam auth provider has no notebook-cluster resource
    samAuthProvider.samClient.notebookClusters shouldBe empty
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "status") shouldBe false
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "connect") shouldBe false
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "sync") shouldBe false
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "delete") shouldBe false

    // creating a cluster would call notify
    samAuthProvider.notifyClusterCreated(userInfo.userEmail, project, name1).futureValue

    // check the resource exists for the user and actions
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "status") shouldBe true
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "connect") shouldBe true
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "sync") shouldBe true
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "delete") shouldBe true

    // deleting a cluster would call notify
    samAuthProvider.notifyClusterDeleted(userInfo.userEmail, userInfo.userEmail, project, name1).futureValue

    samAuthProvider.samClient.notebookClusters shouldBe empty
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "status") shouldBe false
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "connect") shouldBe false
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "sync") shouldBe false
    samAuthProvider.samClient.hasActionOnNotebookClusterResource(userInfo, project, name1, "delete") shouldBe false

    samAuthProvider.samClient.billingProjects.remove((project, userInfo.userEmail))

  }

  "hasProjectPermission should return true if user has project permissions and false if they do not" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    samAuthProvider.samClient.billingProjects += (project, userInfo.userEmail) -> Set("launch_notebook_cluster")
    samAuthProvider.hasProjectPermission(userInfo, CreateClusters, project).futureValue shouldBe true

    samAuthProvider.hasProjectPermission(unauthorizedUserInfo, CreateClusters, project).futureValue shouldBe false
    samAuthProvider.hasProjectPermission(userInfo, CreateClusters, GoogleProject("leo-fake-project")).futureValue shouldBe false

    samAuthProvider.samClient.billingProjects.remove((project, userInfo.userEmail))
  }

  "hasNotebookClusterPermission should return true if user has notebook cluster permissions and false if they do not" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    samAuthProvider.samClient.notebookClusters += (project, name1, userInfo.userEmail) -> Set("sync")
    samAuthProvider.hasNotebookClusterPermission(userInfo, SyncDataToCluster, project, name1).futureValue shouldBe true

    samAuthProvider.hasNotebookClusterPermission(unauthorizedUserInfo, SyncDataToCluster, project, name1).futureValue shouldBe false
    samAuthProvider.hasNotebookClusterPermission(userInfo, DeleteCluster, project, name1).futureValue shouldBe false
    samAuthProvider.hasNotebookClusterPermission(userInfo, SyncDataToCluster, GoogleProject("leo-fake-project"), name1).futureValue shouldBe false
    samAuthProvider.hasNotebookClusterPermission(userInfo, SyncDataToCluster, project, ClusterName("fake-cluster")).futureValue shouldBe false

    samAuthProvider.samClient.notebookClusters.remove((project, name1, userInfo.userEmail))
  }

  "hasNotebookClusterPermission should return true if user does not have notebook cluster permissions but does have project permissions" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    samAuthProvider.samClient.billingProjects += (project, userInfo.userEmail) -> Set("sync_notebook_cluster")
    samAuthProvider.samClient.notebookClusters += (project, name1, userInfo.userEmail) -> Set()

    samAuthProvider.hasNotebookClusterPermission(userInfo, SyncDataToCluster, project, name1).futureValue shouldBe true

    samAuthProvider.samClient.billingProjects.remove((project, userInfo.userEmail))
    samAuthProvider.samClient.notebookClusters.remove((project, name1, userInfo.userEmail))
  }

  "notifyClusterCreated should create a new cluster resource" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    samAuthProvider.samClient.notebookClusters shouldBe empty
    samAuthProvider.notifyClusterCreated(userInfo.userEmail, project, name1).futureValue
    samAuthProvider.samClient.notebookClusters should contain ((project, name1, userInfo.userEmail) -> Set("connect", "read_policies", "status", "delete", "sync"))
    samAuthProvider.samClient.notebookClusters.remove((project, name1, userInfo.userEmail))
  }

  "notifyClusterDeleted should delete a cluster resource" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider
    samAuthProvider.samClient.notebookClusters += (project, name1, userInfo.userEmail) -> Set()

    samAuthProvider.notifyClusterDeleted(userInfo.userEmail, userInfo.userEmail, project, name1).futureValue
    samAuthProvider.samClient.notebookClusters should not contain ((project, name1, userInfo.userEmail) -> Set("connect", "read_policies", "status", "delete", "sync"))
  }

  "should cache hasNotebookClusterPermission results" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    // cache should be empty
    samAuthProvider.notebookAuthCache.size shouldBe 0

    // populate backing samClient
    samAuthProvider.samClient.notebookClusters += (project, name1, userInfo.userEmail) -> Set("sync")

    // call provider method
    samAuthProvider.hasNotebookClusterPermission(userInfo, SyncDataToCluster, project, name1).futureValue shouldBe true

    // cache should contain 1 entry
    samAuthProvider.notebookAuthCache.size shouldBe 1
    val key = NotebookAuthCacheKey(userInfo, SyncDataToCluster, project, name1, implicitly[ExecutionContext])
    samAuthProvider.notebookAuthCache.asMap.containsKey(key) shouldBe true
    samAuthProvider.notebookAuthCache.asMap.get(key).futureValue shouldBe true

    // remove info from samClient
    samAuthProvider.samClient.notebookClusters.remove((project, name1, userInfo.userEmail))

    // provider should still return true because the info is cached
    samAuthProvider.hasNotebookClusterPermission(userInfo, SyncDataToCluster, project, name1).futureValue shouldBe true
  }

  "should consider userInfo objects with different tokenExpiresIn values as the same" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    // cache should be empty
    samAuthProvider.notebookAuthCache.size shouldBe 0

    // populate backing samClient
    samAuthProvider.samClient.notebookClusters += (project, name1, userInfo.userEmail) -> Set("sync")

    // call provider method
    samAuthProvider.hasNotebookClusterPermission(userInfo, SyncDataToCluster, project, name1).futureValue shouldBe true

    samAuthProvider.hasNotebookClusterPermission(userInfo.copy(tokenExpiresIn = userInfo.tokenExpiresIn + 10), SyncDataToCluster, project, name1).futureValue shouldBe true

    samAuthProvider.notebookAuthCache.size shouldBe 1

  }

  "filterClusters should return clusters that were created by the user or whose project is owned by the user" in isolatedDbTest {
    val samAuthProvider = getSamAuthProvider

    // initial filterClusters should return empty list
    samAuthProvider.filterUserVisibleClusters(userInfo, List(project -> name1, project -> name2)).futureValue shouldBe List.empty

    // pretend user created name2
    samAuthProvider.samClient.clusterCreators += userInfo.userEmail -> Set(project -> name2)

    // name2 should now be returned
    samAuthProvider.filterUserVisibleClusters(userInfo, List(project -> name1, project -> name2)).futureValue shouldBe List(project -> name2)

    // pretend user owns the project
    samAuthProvider.samClient.projectOwners += userInfo.userEmail -> Set(project)

    // name1 and name2 should now be returned
    samAuthProvider.filterUserVisibleClusters(userInfo, List(project -> name1, project -> name2)).futureValue shouldBe List(project -> name1, project -> name2)
  }

  "notifyClusterCreated should retry errors and invalidate the pet token cache" in isolatedDbTest {
    logger.info("testing retries, stack traces expected")

    // should retry 401s
    val samClientThrowing401 = mock[MockSwaggerSamClient]
    when {
      samClientThrowing401.createNotebookClusterResource(mockitoEq(userInfo.userEmail), mockitoEq(project), mockitoEq(name1))
    } thenThrow new ApiException(401, "unauthorized")

    val providerThrowing401 = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"), serviceAccountProvider) {
      override lazy val samClient = samClientThrowing401
    }

    providerThrowing401.notifyClusterCreated(userInfo.userEmail, project, name1).failed.futureValue shouldBe a [ApiException]
    verify(samClientThrowing401, times(3)).invalidatePetAccessToken(mockitoEq(userInfo.userEmail), mockitoEq(project))

    // should retry 500s
    val samClientThrowing500 = mock[MockSwaggerSamClient]
    when {
      samClientThrowing500.createNotebookClusterResource(mockitoEq(userInfo.userEmail), mockitoEq(project), mockitoEq(name1))
    } thenThrow new ApiException(500, "internal error")

    val providerThrowing500 = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"), serviceAccountProvider) {
      override lazy val samClient = samClientThrowing500
    }

    providerThrowing500.notifyClusterCreated(userInfo.userEmail, project, name1).failed.futureValue shouldBe a [ApiException]
    verify(samClientThrowing500, times(3)).invalidatePetAccessToken(mockitoEq(userInfo.userEmail), mockitoEq(project))

    // should not retry 404s
    val samClientThrowing404 = mock[MockSwaggerSamClient]
    when {
      samClientThrowing404.createNotebookClusterResource(mockitoEq(userInfo.userEmail), mockitoEq(project), mockitoEq(name1))
    } thenThrow new ApiException(404, "not found")

    val providerThrowing404 = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"), serviceAccountProvider) {
      override lazy val samClient = samClientThrowing404
    }

    providerThrowing404.notifyClusterCreated(userInfo.userEmail, project, name1).failed.futureValue shouldBe a [ApiException]
    verify(samClientThrowing404, never).invalidatePetAccessToken(any[WorkbenchEmail], any[GoogleProject])
  }

  "notifyClusterDeleted should retry errors and invalidate the pet token cache" in isolatedDbTest {
    logger.info("testing retries, stack traces expected")

    // should retry 401s
    val samClientThrowing401 = mock[MockSwaggerSamClient]
    when {
      samClientThrowing401.deleteNotebookClusterResource(mockitoEq(userInfo.userEmail), mockitoEq(project), mockitoEq(name1))
    } thenThrow new ApiException(401, "unauthorized")

    val providerThrowing401 = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"), serviceAccountProvider) {
      override lazy val samClient = samClientThrowing401
    }

    providerThrowing401.notifyClusterDeleted(userInfo.userEmail, userInfo.userEmail, project, name1).failed.futureValue shouldBe a [ApiException]
    verify(samClientThrowing401, times(3)).invalidatePetAccessToken(mockitoEq(userInfo.userEmail), mockitoEq(project))

    // should retry 500s
    val samClientThrowing500 = mock[MockSwaggerSamClient]
    when {
      samClientThrowing500.deleteNotebookClusterResource(mockitoEq(userInfo.userEmail), mockitoEq(project), mockitoEq(name1))
    } thenThrow new ApiException(500, "internal error")

    val providerThrowing500 = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"), serviceAccountProvider) {
      override lazy val samClient = samClientThrowing500
    }

    providerThrowing500.notifyClusterDeleted(userInfo.userEmail, userInfo.userEmail, project, name1).failed.futureValue shouldBe a [ApiException]
    verify(samClientThrowing500, times(3)).invalidatePetAccessToken(mockitoEq(userInfo.userEmail), mockitoEq(project))

    // should not retry 400s
    val samClientThrowing400 = mock[MockSwaggerSamClient]
    when {
      samClientThrowing400.deleteNotebookClusterResource(mockitoEq(userInfo.userEmail), mockitoEq(project), mockitoEq(name1))
    } thenThrow new ApiException(400, "bad request")

    val providerThrowing400 = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"), serviceAccountProvider) {
      override lazy val samClient = samClientThrowing400
    }

    providerThrowing400.notifyClusterDeleted(userInfo.userEmail, userInfo.userEmail, project, name1).failed.futureValue shouldBe a [ApiException]
    verify(samClientThrowing400, never).invalidatePetAccessToken(any[WorkbenchEmail], any[GoogleProject])
  }

  "notifyClusterDeleted should recover 404s from Sam" in isolatedDbTest {
    val samClientThrowing404 = mock[MockSwaggerSamClient]
    when {
      samClientThrowing404.deleteNotebookClusterResource(mockitoEq(userInfo.userEmail), mockitoEq(project), mockitoEq(name1))
    } thenThrow new ApiException(404, "not found")

    val providerThrowing404 = new TestSamAuthProvider(config.getConfig("auth.samAuthProviderConfig"), serviceAccountProvider) {
      override lazy val samClient = samClientThrowing404
    }

    // provider should not throw an exception
    providerThrowing404.notifyClusterDeleted(userInfo.userEmail, userInfo.userEmail, project, name1).futureValue shouldBe (())
    // request should not have been retried
    verify(samClientThrowing404, never).invalidatePetAccessToken(any[WorkbenchEmail], any[GoogleProject])
  }

}
