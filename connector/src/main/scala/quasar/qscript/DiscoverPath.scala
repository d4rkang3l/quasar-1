/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.qscript

import quasar.Predef._
import quasar.Planner.NoFilesFound
import quasar.contrib.pathy._
import quasar.contrib.scalaz._
import quasar.fp._
import quasar.fp.ski._
import quasar.fs._
import quasar.qscript.MapFuncs._

import matryoshka._
import matryoshka.data._
import matryoshka.implicits._
import matryoshka.patterns._
import pathy.Path.{dir1, file1, rootDir}
import scalaz._, Scalaz._, \&/._

/** This extracts statically-known paths from QScript queries to make it easier
  * for connectors to map queries to their own filesystems.
  */
trait DiscoverPath[IN[_]] {
  type IT[F[_]]
  type OUT[A]

  def discoverPath[M[_]: Monad: MonadFsErr](g: DiscoverPath.ListContents[M])
      : AlgebraM[M, IN, List[ADir] \&/ IT[OUT]]
}

object DiscoverPath extends DiscoverPathInstances {
  type Aux[T[_[_]], IN[_], F[_]] = DiscoverPath[IN] {
    type IT[F[_]] = T[F]
    type OUT[A] = F[A]
  }

  type ListContents[M[_]] = ADir => M[Set[PathSegment]]

  def apply[T[_[_]], IN[_], OUT[_]](implicit ev: DiscoverPath.Aux[T, IN, OUT]) =
    ev

  def unionAll[T[_[_]]: BirecursiveT, M[_]: Monad: MonadFsErr, OUT[_]: Functor]
    (g: ListContents[M])
    (implicit
      R: Const[Read[APath], ?] :<: OUT,
      QC:    QScriptCore[T, ?] :<: OUT,
      FI: Injectable.Aux[OUT, QScriptTotal[T, ?]])
      : List[ADir] \&/ T[OUT] => M[T[OUT]] =
    discoverPath[T, OUT].unionAll[M](g)
}

abstract class DiscoverPathInstances {
  import DiscoverPath.ListContents

  def discoverPath[T[_[_]]: BirecursiveT, O[_]: Functor]
    (implicit
      R: Const[Read[APath], ?] :<: O,
      QC:    QScriptCore[T, ?] :<: O,
      FI: Injectable.Aux[O, QScriptTotal[T, ?]]) =
    new DiscoverPathT[T, O]

  // real instances

  implicit def deadEnd[T[_[_]], F[_]]: DiscoverPath.Aux[T, Const[DeadEnd, ?], F] =
    new DiscoverPath[Const[DeadEnd, ?]] {
      type IT[F[_]] = T[F]
      type OUT[A] = F[A]

      def discoverPath[M[_]: Monad: MonadFsErr](g: ListContents[M]) =
        κ(-\&/[List[ADir], T[OUT]](List(rootDir)).point[M])
    }

  implicit def projectBucket[T[_[_]]: BirecursiveT, F[_]: Functor]
    (implicit
      R: Const[Read[APath], ?] :<: F,
      QC:    QScriptCore[T, ?] :<: F,
      PB:  ProjectBucket[T, ?] :<: F,
      FI: Injectable.Aux[F, QScriptTotal[T, ?]])
      : DiscoverPath.Aux[T, ProjectBucket[T, ?], F] =
    discoverPath[T, F].projectBucket

  implicit def qscriptCore[T[_[_]]: BirecursiveT, F[_]: Functor]
    (implicit
      R: Const[Read[APath], ?] :<: F,
      QC:    QScriptCore[T, ?] :<: F,
      FI: Injectable.Aux[F, QScriptTotal[T, ?]])
      : DiscoverPath.Aux[T, QScriptCore[T, ?], F] =
    discoverPath[T, F].qscriptCore

  // branch handling

  implicit def thetaJoin[T[_[_]]: BirecursiveT, F[_]: Functor]
    (implicit
      R: Const[Read[APath], ?] :<: F,
      QC:    QScriptCore[T, ?] :<: F,
      TJ:      ThetaJoin[T, ?] :<: F,
      FI: Injectable.Aux[F, QScriptTotal[T, ?]])
      : DiscoverPath.Aux[T, ThetaJoin[T, ?], F] =
    discoverPath[T, F].thetaJoin

  implicit def equiJoin[T[_[_]]: BirecursiveT, F[_]: Functor]
    (implicit
      R: Const[Read[APath], ?] :<: F,
      QC:    QScriptCore[T, ?] :<: F,
      EJ:       EquiJoin[T, ?] :<: F,
      FI: Injectable.Aux[F, QScriptTotal[T, ?]])
      : DiscoverPath.Aux[T, EquiJoin[T, ?], F] =
    discoverPath[T, F].equiJoin

  implicit def coproduct[T[_[_]], F[_], G[_], H[_]]
    (implicit F: DiscoverPath.Aux[T, F, H], G: DiscoverPath.Aux[T, G, H])
      : DiscoverPath.Aux[T, Coproduct[F, G, ?], H] =
    new DiscoverPath[Coproduct[F, G, ?]] {
      type IT[F[_]] = T[F]
      type OUT[A] = H[A]

      def discoverPath[M[_]: Monad: MonadFsErr](g: ListContents[M]) =
        _.run.fold(F.discoverPath(g), G.discoverPath(g))
    }

  implicit def read[T[_[_]]: BirecursiveT, F[_]: Functor, A]
    (implicit
      R: Const[Read[APath], ?] :<: F,
      QC:    QScriptCore[T, ?] :<: F,
      RA:    Const[Read[A], ?] :<: F,
      FI: Injectable.Aux[F, QScriptTotal[T, ?]])
      : DiscoverPath.Aux[T, Const[Read[A], ?], F] =
    discoverPath[T, F].default[Const[Read[A], ?]]

  implicit def shiftedRead[T[_[_]]: BirecursiveT, F[_]: Functor, A]
    (implicit
      R:     Const[Read[APath], ?] :<: F,
      QC:        QScriptCore[T, ?] :<: F,
      IN: Const[ShiftedRead[A], ?] :<: F,
      FI: Injectable.Aux[F, QScriptTotal[T, ?]])
      : DiscoverPath.Aux[T, Const[ShiftedRead[A], ?], F] =
    discoverPath[T, F].default[Const[ShiftedRead[A], ?]]
}

private[qscript] final class DiscoverPathT[T[_[_]]: BirecursiveT, O[_]: Functor](
  implicit
  R: Const[Read[APath], ?]       :<: O,
  QC:          QScriptCore[T, ?] :<: O,
  FI: Injectable.Aux[O, QScriptTotal[T, ?]]
) extends TTypes[T] {
  import DiscoverPath.ListContents

  private def DiscoverPathTotal = DiscoverPath[T, QScriptTotal, QScriptTotal]
  private def DiscoverPathTTotal = new DiscoverPathT[T, QScriptTotal]

  private def union(elems: NonEmptyList[T[O]]): T[O] =
    elems.foldRight1(
      (elem, acc) => QC.inj(Union(QC.inj(Unreferenced[T, T[O]]()).embed,
        elem.cata[FreeQS](g => Free.roll(FI.inject(g))),
        acc.cata[FreeQS](g => Free.roll(FI.inject(g))))).embed)

  private def makeRead[F[_]](path: ADir)(implicit R: Const[Read[APath], ?] :<: F):
      F[T[F]] =
    R.inj(Const[Read[APath], T[F]](Read(path)))

  private def wrapDir[F[_]: Functor]
    (name: String, d: F[T[F]])
    (implicit QC: QScriptCore :<: F)
      : F[T[F]] =
    QC.inj(Map(d.embed, Free.roll(MakeMap(StrLit(name), HoleF))))

  private val unionDirs: List[ADir] => Option[NonEmptyList[T[O]]] =
    _ ∘ (makeRead[O](_).embed) match {
      case Nil    => None
      case h :: t => NonEmptyList.nel(h, t.toIList).some
    }

  def unionAll[M[_]: Monad: MonadFsErr](g: ListContents[M]): List[ADir] \&/ T[O] => M[T[O]] =
    _.fold(
      ds => unionDirs(ds).fold[M[T[O]]](
        MonadFsErr[M].raiseError(FileSystemError.qscriptPlanningFailed(NoFilesFound(ds))))(
        union(_).point[M]),
      _.point[M],
      (ds, qs) => unionDirs(ds).fold(qs)(d => union(qs <:: d)).point[M])

  private def convertBranch[M[_]: Monad: MonadFsErr]
    (src: List[ADir] \&/ T[O], branch: FreeQS)
    (f: ListContents[M])
      : M[FreeQS] =
    branch.cataM[M, List[ADir] \&/ T[QScriptTotal]](
      interpretM(
        κ((src ∘ (_.transCata[T[QScriptTotal]](FI.inject))).point[M]),
        DiscoverPathTotal.discoverPath(f))) >>=
      (DiscoverPathTTotal.unionAll[M](f).apply(_) ∘ (_.cata(Free.roll[QScriptTotal, Hole])))

  private def convertBranchingOp[M[_]: Monad: MonadFsErr]
    (src: List[ADir] \&/ T[O], lb: FreeQS, rb: FreeQS, f: ListContents[M])
    (op: (T[O], FreeQS, FreeQS) => O[T[O]])
      : M[List[ADir] \&/ T[O]] =
    (convertBranch(src, lb)(f) ⊛ convertBranch(src, rb)(f))((l, r) =>
      \&/-(op(QC.inj(Unreferenced[T, T[O]]()).embed, l, r).embed))

  def fileType[M[_]: Monad: MonadFsErr](listContents: ListContents[M]):
      (ADir, String) => OptionT[M, ADir \/ AFile] =
    (dir, name) => OptionT(MonadFsErr[M].handleError(listContents(dir).map(_.some))(κ(none.point[M]))) >>=
      (cont => OptionT((cont.find(_.fold(_.value ≟ name, _.value ≟ name)) ∘
        (_.bimap(dir </> dir1(_), dir </> file1(_)))).point[M]))

  // real instances

  def projectBucket(implicit PB: ProjectBucket :<: O): DiscoverPath.Aux[T, ProjectBucket, O] =
    new DiscoverPath[ProjectBucket] {
      type IT[F[_]] = T[F]
      type OUT[A] = O[A]

      def handleDirs[M[_]: Monad: MonadFsErr](g: ListContents[M], dirs: List[ADir], field: String) =
        dirs.traverseM(fileType(g).apply(_, field).fold(
          df => List(df ∘ (file => R.inj(Const[Read[APath], T[OUT]](Read(file))).embed)),
          Nil)) ∘ {
          case Nil => -\&/(Nil)
          case h :: t => t.foldRight(h.fold(d => -\&/(List(d)), \&/-(_)))((elem, acc) =>
            elem.fold(
              d => acc match {
                case This(ds) => -\&/(d :: ds)
                case That(qs) => Both(List(d), qs)
                case Both(ds, qs) => Both(d :: ds, qs)
              },
              f => acc match {
                case This(ds) => Both(ds, f)
                case That(qs) => That(union(NonEmptyList(f, qs)))
                case Both(ds, qs) => Both(ds, union(NonEmptyList(f, qs)))
              }))
        }

      def rebucket(out: T[OUT], value: FreeMap, field: String) =
        PB.inj(BucketField(out, value, StrLit(field))).embed

      def discoverPath[M[_]: Monad: MonadFsErr](g: ListContents[M]) = {
        // FIXME: `value` must be `HoleF`.
        case BucketField(src, value, StrLit(field)) =>
          src.fold(
            handleDirs(g, _, field),
            out => \&/-(rebucket(out, value, field)).point[M],
            (dirs, out) => handleDirs(g, dirs, field) ∘ {
              case This(dirs)        => Both(dirs, rebucket(out, value, field))
              case That(files)       => That(union(NonEmptyList(files, rebucket(out, value, field))))
              case Both(dirs, files) => Both(dirs, union(NonEmptyList(files, rebucket(out, value, field))))
            })
        case x => x.traverse(unionAll(g)) ∘ (in => \&/-(PB.inj(in).embed))
      }
    }

  def qscriptCore: DiscoverPath.Aux[T, QScriptCore, O] =
    new DiscoverPath[QScriptCore] {
      type IT[F[_]] = T[F]
      type OUT[A] = O[A]

      def discoverPath[M[_]: Monad: MonadFsErr](g: ListContents[M]) = {
        case Union(src, lb, rb) if !src.isThat =>
          convertBranchingOp(src, lb, rb, g)((s, l, r) =>
            QC.inj(Union(s, l, r)))
        case Subset(src, lb, sel, rb) if !src.isThat =>
          convertBranchingOp(src, lb, rb, g)((s, l, r) =>
            QC.inj(Subset(s, l, sel, r)))

        case x => x.traverse(unionAll(g)) ∘ (in => \&/-(QC.inj(in).embed))
      }
    }

  // branch handling

  def thetaJoin(implicit TJ: ThetaJoin :<: O): DiscoverPath.Aux[T, ThetaJoin, O] =
    new DiscoverPath[ThetaJoin] {
      type IT[F[_]] = T[F]
      type OUT[A] = O[A]

      def discoverPath[M[_]: Monad: MonadFsErr](g: ListContents[M]) = {
        case ThetaJoin(src, lb, rb, on, jType, combine) if !src.isThat =>
          convertBranchingOp(src, lb, rb, g)((s, l, r) =>
            TJ.inj(ThetaJoin(s, l, r, on, jType, combine)))
        case x => x.traverse(unionAll(g)) ∘ (in => \&/-(TJ.inj(in).embed))
      }
    }

  def equiJoin(implicit EJ: EquiJoin :<: O): DiscoverPath.Aux[T, EquiJoin, O] =
    new DiscoverPath[EquiJoin] {
      type IT[F[_]] = T[F]
      type OUT[A] = O[A]

      def discoverPath[M[_]: Monad: MonadFsErr](g: ListContents[M]) = {
        case EquiJoin(src, lb, rb, lk, rk, jType, combine) if !src.isThat =>
          convertBranchingOp(src, lb, rb, g)((s, l, r) =>
            EJ.inj(EquiJoin(s, l, r, lk, rk, jType, combine)))
        case x => x.traverse(unionAll(g)) ∘ (in => \&/-(EJ.inj(in).embed))
      }
    }

  def default[IN[_]: Traverse](implicit IN: IN :<: O): DiscoverPath.Aux[T, IN, O] =
    new DiscoverPath[IN] {
      type IT[F[_]] = T[F]
      type OUT[A] = O[A]

      def discoverPath[M[_]: Monad: MonadFsErr](g: ListContents[M]) =
        _.traverse(unionAll(g)) ∘ (in => \&/-(IN.inj(in).embed))
    }
}
