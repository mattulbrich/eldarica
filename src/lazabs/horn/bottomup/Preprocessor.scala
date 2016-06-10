/**
 * Copyright (c) 2011-2014 Philipp Ruemmer. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * 
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * 
 * * Neither the name of the authors nor the names of their
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package lazabs.horn.bottomup

import lazabs.horn.bottomup.HornClauses._
import lazabs.horn.global._
import ap.basetypes.IdealInt
import ap.parser._
import IExpression._
import lazabs.prover.PrincessWrapper
import ap.SimpleAPI
import SimpleAPI.ProverStatus
import ap.util.Seqs

import scala.collection.mutable.{HashSet => MHashSet, HashMap => MHashMap,
                                 LinkedHashSet, ArrayBuffer}


class HornPreprocessor(
           oriClauses : Seq[HornClauses.Clause],
           oriInitialPredicates : Map[IExpression.Predicate, Seq[IFormula]]) {
  import IExpression._

  Console.err.println
  Console.err.println("Initially:                " + oriClauses.size + " clauses")

  private val maxClauseBodySize = 3

  private var tempPredCounter = 0

  //////////////////////////////////////////////////////////////////////////////

  val (result, initialPredicates) = {
    val clauses1 = oriClauses
    val initialPreds1 = oriInitialPredicates

    // inline simple definitions found in the clause constraints
    val clauses2 = for (clause@Clause(head, oriBody, constraint) <- clauses1) yield {
      val headSyms = SymbolCollector constants head
      var body = oriBody

      var conjuncts = LineariseVisitor(constraint, IBinJunctor.And)
      val oriConjunctNum = conjuncts.size
      val replacement = new MHashMap[ConstantTerm, ITerm]
      val replacedConsts = new MHashSet[ConstantTerm]

      var changed = true
      while (changed) {
        changed = false

        val remConjuncts = conjuncts filter {
          case IIntFormula(IIntRelation.EqZero,
                           IPlus(IConstant(c), 
                                 ITimes(IdealInt.MINUS_ONE, IConstant(d))))
            if (c == d) =>
              false
          case IIntFormula(IIntRelation.EqZero,
                           IPlus(IConstant(c), 
                                 ITimes(IdealInt.MINUS_ONE, IConstant(d))))
            if (c != d && !(replacedConsts contains c) && !(replacedConsts contains d)) =>
              if (!(headSyms contains c)) {
                replacement.put(c, IConstant(d))
                replacedConsts += c
                replacedConsts += d
                false
              } else if (!(headSyms contains d)) {
                replacement.put(d, IConstant(c))
                replacedConsts += c
                replacedConsts += d
                false
              } else {
                true
              }
          case _ => true
        }

        if (!replacement.isEmpty) {
          changed = true
          conjuncts =
            for (f <- remConjuncts) yield ConstantSubstVisitor(f, replacement)

          body = for (a <- body)
                 yield ConstantSubstVisitor(a, replacement).asInstanceOf[IAtom]

          replacement.clear
          replacedConsts.clear
        }
      }

      if (oriConjunctNum != conjuncts.size)
        Clause(head, body, and(conjuncts))
      else
        clause
    }

    val (clauses3, initialPreds3) = elimLinearDefs(clauses2, initialPreds1)

    Console.err.println("After inlining:           " + clauses3.size + " clauses")

/*
    val (clauses4, initialPreds4) = {
      var initialPreds = initialPreds3
      val clauses4 =
        for (c <- clauses3;
             d <- {
               val (newClauses, newPreds) = splitClauseBody(c, initialPreds)
               initialPreds = newPreds
               newClauses
             }) yield d
      (clauses4, initialPreds)
    }
*/

/*
    val (clauses4, initialPreds4) =
      (for (c <- clauses3;
            d <- splitClauseBody2(c, initialPreds3)) yield d,
       initialPreds3)
*/

    val (clauses4, initialPreds4) = splitClauseBodies3(clauses3, initialPreds3)

    Console.err.println("After shortening clauses: " + clauses4.size + " clauses")

    val clauses5 =
      if (lazabs.GlobalParameters.get.splitClauses) SimpleAPI.withProver { p =>
        // turn the resulting formula into DNF, and split positive equations
        // (which often gives better performance)

        import p._

        val newClauses = new ArrayBuffer[Clause]

        for (clause@Clause(head, body, constraint) <- clauses4) scope {
          addConstantsRaw(SymbolCollector constantsSorted constraint)
          for (d <- ap.PresburgerTools.nonDNFEnumDisjuncts(asConjunction(constraint)))
            for (f <- splitPosEquations(Transform2NNF(!asIFormula(d)))) {
              if (newClauses.size % 100 == 0)
                lazabs.GlobalParameters.get.timeoutChecker()
              newClauses += Clause(head, body, Transform2NNF(!f))
            }
        }

        Console.err.println("After splitting clauses:  " + newClauses.size + " clauses")

        newClauses

//        (for (Clause(head, body, constraint) <- clauses4.iterator;
//              constraint2 <- splitConstraint(~constraint))
//         yield Clause(head, body, Transform2NNF(!constraint2))).toList
      } else {
        clauses4
      }

/*
    val clauses5 =
      for (Clause(head, body, constraint) <- clauses4;
           cnf = lazabs.horn.parser.HornReader.CNFSimplifier(!constraint);
           constraint2 <- LineariseVisitor(cnf, IBinJunctor.And))
      yield Clause(head, body, Transform2NNF(!constraint2))
*/

    // apply acceleration of Horn clauses
    val clauses6 = if (!lazabs.GlobalParameters.get.staticAccelerate) {
      clauses5
    } else {
      val newClauses = HornAccelerate.accelerate(clauses5)
      Console.err.println("After acceleration:       " + newClauses.size + " clauses")
      newClauses
    }

    Console.err.println
    
    (clauses6, initialPreds4)
  }

  //////////////////////////////////////////////////////////////////////////////

  def elimLinearDefs(allClauses : Seq[HornClauses.Clause],
                     allInitialPreds : Map[Predicate, Seq[IFormula]])
                    : (Seq[HornClauses.Clause], Map[Predicate, Seq[IFormula]]) = {
    var changed = true
    var clauses = allClauses
    var initialPreds = allInitialPreds

    while (changed) {
      changed = false

      // determine relation symbols that are uniquely defined

      val uniqueDefs = extractUniqueDefs(clauses)
      val finalDefs = extractAcyclicDefs(uniqueDefs)

      val newClauses =
        for (clause@Clause(IAtom(p, _), _, _) <- clauses;
             if (!(finalDefs contains p)))
        yield applyDefs(clause, finalDefs)

      if (newClauses.size < clauses.size) {
        clauses = newClauses
        changed = true
      }

      initialPreds = initialPreds -- finalDefs.keys
    }

    (clauses, initialPreds)
  }

  //////////////////////////////////////////////////////////////////////////////

  def extractUniqueDefs(clauses : Iterable[Clause]) = {
     val uniqueDefs = new MHashMap[Predicate, Clause]
     val badHeads = new MHashSet[Predicate]
     badHeads += FALSE

     for (clause@Clause(head, body, constraint) <- clauses)
       if (!(badHeads contains head.pred)) {
         if ((uniqueDefs contains head.pred) ||
             body.size > 1 ||
             (body.size == 1 && head.pred == body.head.pred) ||
             head.pred.toString().startsWith("INIT/")) {
           badHeads += head.pred
           uniqueDefs -= head.pred
         } else {
           uniqueDefs.put(head.pred, clause)
         }
       }

    uniqueDefs.toMap
  }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Extract an acyclic subset of the definitions, and
   * shorten definition paths
   */
  def extractAcyclicDefs(uniqueDefs : Map[Predicate, Clause]) = {
    var remDefs = new MHashMap[Predicate, Clause] ++ uniqueDefs
    val finalDefs = new MHashMap[Predicate, Clause]

    while (!remDefs.isEmpty) {
      val bodyPreds =
        (for (Clause(_, body, _) <- remDefs.valuesIterator;
              IAtom(p, _) <- body.iterator)
         yield p).toSet
      val (remainingDefs, defsToInline) =
        remDefs partition { case (p, _) => bodyPreds contains p }

      if (defsToInline.isEmpty) {
        // we have to drop some definitions to eliminate cycles
        val pred =
          (for (Clause(IAtom(p, _), _, _) <- oriClauses.iterator;
                if ((remDefs contains p) && (bodyPreds contains p)))
           yield p).next

        remDefs retain {
          case (_, Clause(_, Seq(), _)) => true
          case (_, Clause(_, Seq(IAtom(p, _)), _)) => p != pred
        }
      } else {
        remDefs = remainingDefs

        finalDefs transform {
          case (_, clause) => applyDefs(clause, defsToInline)
        }

        finalDefs ++= defsToInline
      }
    }

    finalDefs.toMap
  }

  //////////////////////////////////////////////////////////////////////////////

  def applyDefs(clause : Clause,
                defs : scala.collection.Map[Predicate, Clause]) : Clause = {
    val Clause(head, body, constraint) = clause
    assert(!(defs contains head.pred))

    var changed = false

    val (newBody, newConstraints) = (for (bodyLit@IAtom(p, args) <- body) yield {
      (defs get p) match {
        case None =>
          (List(bodyLit), i(true))
        case Some(defClause) => {
          changed = true
          defClause inline args
        }
      }
    }).unzip

    if (changed)
      Clause(head, newBody.flatten, constraint & and(newConstraints))
    else
      clause
  }

  //////////////////////////////////////////////////////////////////////////////

  def splitClauseBody(clause : Clause,
                      initialPreds : Map[Predicate, Seq[IFormula]])
                     : (List[Clause], Map[Predicate, Seq[IFormula]]) = {
    val Clause(head, body, constraint) = clause

    if (body.size > 2) {
      val body1 = body take 2
      val remainingBody = body drop 2

      val body1Syms =
        (for (a <- body1;
              c <- SymbolCollector constantsSorted a) yield c).distinct
      val body1SymsSet = body1Syms.toSet

      val (constraintList1, constraintList2) =
        LineariseVisitor(Transform2NNF(constraint), IBinJunctor.And) partition {
          f => (SymbolCollector constants f) subsetOf body1SymsSet
        }

      val constraint1 = and(constraintList1)
      val constraint2 = and(constraintList2)

      val otherSyms =
        SymbolCollector constants (constraint2 & and(remainingBody) & head)

      val commonSyms = (body1Syms filter otherSyms) match {
        case Seq() =>
          // make sure that there is at least one argument, otherwise
          // later assumptions in the model checker will be violated
          List(body1Syms.head)
        case syms => syms
      }
      val tempPred = new Predicate ("temp" + tempPredCounter, commonSyms.size)
      tempPredCounter = tempPredCounter + 1

      val head1 = IAtom(tempPred, commonSyms)

      val newClause =
        Clause(head1, body1, constraint1)
      val (nextClause :: otherClauses, newInitialPreds) =
        splitClauseBody(Clause(head, head1 :: remainingBody, constraint2),
                        initialPreds)

      val Clause(nextHead, Seq(_, nextBodyLit), nextConstraint) = nextClause

      val newInitialPreds2 =
      (newInitialPreds get nextHead.pred) match {
        case Some(preds) if (!preds.isEmpty) => SimpleAPI.withProver { p =>
          import p._

          setMostGeneralConstraints(true)
          addConstantsRaw(nextClause.constants.toSeq.sortBy(_.name))
          makeExistentialRaw(SymbolCollector constants head1)

          !! (nextConstraint)

          val headInitialPreds = new LinkedHashSet[IFormula]
          val backSubst = (for ((t, n) <- commonSyms.iterator.zipWithIndex)
                           yield (t -> v(n))).toMap

          //////////////////////////////////////////////////////////////////////

          def getInitialPred : IFormula = ??? match {
            case ProverStatus.Valid => getConstraint match {
              case f@IBoolLit(true) => f
              case f => ConstantSubstVisitor(f, backSubst)
            }
            case ProverStatus.Invalid =>
              IBoolLit(false)
          }

          //////////////////////////////////////////////////////////////////////

          def testByPreds(remainingPreds : List[(IFormula, Set[ConstantTerm])],
                          uneligiblePreds : List[(IFormula, Set[ConstantTerm])],
                          relevantSyms : Set[ConstantTerm],
                          lastRelevantSymsSize : Int) : Unit =
            remainingPreds match {
              case (byPred, byPredSyms) :: otherByPreds =>
                if (Seqs.disjointSeq(relevantSyms, byPredSyms)) {
                  testByPreds(otherByPreds,
                              (byPred, byPredSyms) :: uneligiblePreds,
                              relevantSyms,
                              lastRelevantSymsSize)
                } else {
                  testByPreds(otherByPreds,
                              uneligiblePreds,
                              relevantSyms,
                              lastRelevantSymsSize)
                  scope {
                    !! (byPred)
                    getInitialPred match {
                      case IBoolLit(true) =>
                        // useless predicate, and no need to search further
                      case f => {
                        headInitialPreds += f
                        testByPreds(otherByPreds,
                                    uneligiblePreds,
                                    relevantSyms ++ byPredSyms,
                                    lastRelevantSymsSize)
                      }
                    }
                  }
                }
              case List() =>
                if (!uneligiblePreds.isEmpty &&
                    relevantSyms.size > lastRelevantSymsSize)
                  testByPreds(uneligiblePreds, List(),
                              relevantSyms, relevantSyms.size)
            }

          val allByPreds =
            for (f <- newInitialPreds.getOrElse(nextBodyLit.pred, List()).toList)
            yield {
              val instF = VariableSubstVisitor(f, (nextBodyLit.args.toList, 0))
              (instF, (SymbolCollector constants instF).toSet)
            }

          //////////////////////////////////////////////////////////////////////

          for (pred <- preds) scope {
            val instPred = VariableSubstVisitor(pred, (nextHead.args.toList, 0))
            ?? (instPred)

            val syms = (SymbolCollector constants (nextConstraint & instPred)).toSet
            testByPreds(allByPreds, List(), syms, syms.size)
          }

          headInitialPreds -= IBoolLit(false)

          newInitialPreds + (tempPred -> headInitialPreds.toSeq)
        }
        case _ => newInitialPreds
      }

      (newClause :: nextClause :: otherClauses, newInitialPreds2)
    } else {
      (List(clause), initialPreds)
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Alternative implementation, creating wider but less deep trees

  def splitClauseBody2(clause : Clause,
                       initialPredicates : Map[IExpression.Predicate, Seq[IFormula]])
                      : List[Clause] =
    if (clause.body.size <= maxClauseBodySize ||
        ((initialPredicates get clause.head.pred) match {
          case Some(s) => !s.isEmpty
          case None => false
        }))
      List(clause)
    else
      splitClauseBody2(clause.head, clause.body,
                       LineariseVisitor(Transform2NNF(clause.constraint),
                                        IBinJunctor.And))

  def splitClauseBody2(head : IAtom,
                       body : List[IAtom],
                       constraint : Seq[IFormula]) : List[Clause] =
    if (body.size <= maxClauseBodySize) {
      List(Clause(head, body, and(constraint)))
    } else {
      val halfSize = (body.size + 1) / 2
      val bodyHalf1 = body take halfSize
      val bodyHalf2 = body drop halfSize

      def findRelevantConstraints(terms : Seq[ITerm]) : Seq[IFormula] = {
        val syms = new MHashSet[ConstantTerm]
        for (t <- terms)
          syms ++= SymbolCollector constants t

/*        val selConstraints = new LinkedHashSet[IFormula]
        var selConstraintsSize = -1
        while (selConstraints.size > selConstraintsSize) {
          selConstraintsSize = selConstraints.size
          for (f <- constraint)
            if (!(selConstraints contains f) &&
                ContainsSymbol(f, (x:IExpression) => x match {
                  case IConstant(c) => syms contains c
                  case _ => false
                })) {
              selConstraints += f
              syms ++= SymbolCollector constants f
            }
        } */

        constraint filter { f => (SymbolCollector constants f) subsetOf syms }

//        selConstraints.toSeq
      }

      val args1 = (for (IAtom(_, a) <- bodyHalf1; t <- a) yield t).distinct
      val half1Constraints = findRelevantConstraints(args1)

      val half1Pred = new Predicate ("temp" + tempPredCounter, args1.size)
      tempPredCounter = tempPredCounter + 1

      val head1 = IAtom(half1Pred, args1)
      val clauses1 = splitClauseBody2(head1, bodyHalf1, half1Constraints)

      val (head2, clauses2) = bodyHalf2 match {
        case Seq(head2) => (head2, List[Clause]())
        case bodyHalf2  => {
          val args2 = (for (IAtom(_, a) <- bodyHalf2; t <- a) yield t).distinct
          val half2Constraints = findRelevantConstraints(args2)

          val half2Pred = new Predicate ("temp" + tempPredCounter, args2.size)
          tempPredCounter = tempPredCounter + 1

          val head2 = IAtom(half2Pred, args2)
          (head2, splitClauseBody2(head2, bodyHalf2, half2Constraints))
        }
      }

      clauses1 ++ clauses2 ++ List(Clause(head, List(head1, head2), and(constraint)))
    }

  //////////////////////////////////////////////////////////////////////////////
  // Alternative implementation, using fewer new predicates

  def splitClauseBodies3(clauses : Seq[Clause],
                         initialPreds : Map[Predicate, Seq[IFormula]])
                        : (List[Clause], Map[Predicate, Seq[IFormula]]) = {
    val allPredicates = new LinkedHashSet[Predicate]
    for (Clause(head, body, _) <- clauses)
      for (IAtom(p, _) <- head :: body)
        allPredicates += p

    val combiningPreds = new ArrayBuffer[(List[(Predicate, Int)], Predicate)]
    val newClauses = new ArrayBuffer[Clause]
    var newInitialPreds = initialPreds

    ////////////////////////////////////////////////////////////////////////////

    def createNewPredicateCounting(predCounts : List[(Predicate, Int)])
                                  : Predicate = {
      assert((predCounts map (_._1)).toSet.size == predCounts.size)

      val allPreds =
        (for ((p, num) <- predCounts; _ <- 0 until num) yield p).toList

      val newPred = new Predicate ((allPreds map (_.name)) mkString "_",
                                   (allPreds map (_.arity)).sum)
      combiningPreds += ((predCounts, newPred))
      allPredicates += newPred

      val definingBody = for ((p, num) <- allPreds.zipWithIndex) yield
        IAtom(p, for (k <- 0 until p.arity)
                 yield i(new ConstantTerm (p.name + "_" + num + "_" + k)))
      val definingHead = IAtom(newPred, for (IAtom(_, args) <- definingBody;
                                             a <- args) yield a)
      newClauses += Clause(definingHead, definingBody, i(true))

      // create initial predicates for the new symbol as well  
      var offset = 0
      var nextOffset = 0
      val initPreds =
        for (p <- allPreds;
             initPred <- {
               offset = nextOffset
               nextOffset = nextOffset + p.arity
               newInitialPreds.getOrElse(p, List())
             })
        yield VariableShiftVisitor(initPred, 0, offset)

      newInitialPreds = newInitialPreds + (newPred -> initPreds)

      newPred
    }

    ////////////////////////////////////////////////////////////////////////////

    for (clause <- clauses)
      newClauses += (
        if (clause.body.size <= maxClauseBodySize) {
          clause
        } else {
          val Clause(head, body, constraint) = clause

          var bodySize = body.size
          val bodyLits = new MHashMap[Predicate, List[IAtom]]
          for ((p, atoms) <- body groupBy (_.pred))
            bodyLits.put(p, atoms.toList)

          def combinePredicates(predCounts : List[(Predicate, Int)],
                                newPred : Predicate) = {
            val allArgs = (for ((pred, num) <- predCounts;
                                _ <- 0 until num;
                                oldAtom = {
                                  val atom :: rest = bodyLits(pred)
                                  bodyLits.put(pred, rest)
                                  atom
                                };
                                a <- oldAtom.args)
                           yield a).toList
            bodyLits.put(newPred,
              bodyLits.getOrElse(newPred, List()) ::: List(IAtom(newPred, allArgs)))
          }

          while (bodySize > maxClauseBodySize) {
            (combiningPreds find {
               case (counts, pred) => counts forall {
                 case (p, num) => bodyLits.getOrElse(p, List()).size >= num
               }
             }) match {

              case Some((predCounts, newPred)) =>
                combinePredicates(predCounts, newPred)

              case None => {
                val selectedPredicates =
                  (allPredicates find {
                     p => bodyLits.getOrElse(p, List()).size >= maxClauseBodySize
                   }) match {
                    case Some(pred) =>
                      List((pred, maxClauseBodySize))
                    case None => {
                      var remaining = maxClauseBodySize
                      val counts = new ArrayBuffer[(Predicate, Int)]

                      val it = allPredicates.iterator
                      while (remaining > 0) {
                        val nextPred = it.next
                        val num = bodyLits.getOrElse(nextPred, List()).size
                        if (num > 0) {
                          val chosen = num min remaining
                          remaining = remaining - chosen
                          counts += ((nextPred, chosen))
                        }
                      }

                      counts.toList
                    }
                  }

                val newPred = createNewPredicateCounting(selectedPredicates)
                combinePredicates(selectedPredicates, newPred)
              }

            }

            bodySize = bodySize - maxClauseBodySize + 1
          }

          val newBody =
            (for (p <- allPredicates.iterator;
                  a <- bodyLits.getOrElse(p, List()).iterator)
             yield a).toList

          Clause(head, newBody, constraint)
        })

    (newClauses.toList, newInitialPreds)
  }

  //////////////////////////////////////////////////////////////////////////////

  object CNFSimplifier extends Simplifier {
    override protected def furtherSimplifications(expr : IExpression) =
      expr match {
        case IBinFormula(IBinJunctor.Or, f,
                         IBinFormula(IBinJunctor.And, g1, g2)) =>
          (f | g1) & (f | g2)
        case IBinFormula(IBinJunctor.Or,
                         IBinFormula(IBinJunctor.And, g1, g2),
                         f) =>
          (g1 | f) & (g2 | f)
        case expr => expr
      }
  }

  def splitPosEquations(f : IFormula) : Seq[IFormula] = {
    val split =
      or(for (g <- LineariseVisitor(f, IBinJunctor.Or)) yield g match {
           case EqZ(t) => geqZero(t) & geqZero(-t)
           case g => g
         })
    LineariseVisitor(CNFSimplifier(split), IBinJunctor.And)
  }

  def splitConstraint(f : IFormula) : Iterator[IFormula] =
    for (g <- LineariseVisitor(CNFSimplifier(f), IBinJunctor.And).iterator;
         h <- splitPosEquations(g).iterator)
    yield h
}
