/**
 * The three risk models, named once.
 *
 * <p>The gauges and the explanations underneath them label the same three models. A
 * question reworded in one place but not the other would have the overview and the
 * reasons for it quietly disagreeing about what a model is even for, so the wording
 * lives here and both read from it.</p>
 */

/** The keys exactly as `SprintRisk` names them, so a panel can index straight into it. */
export const RISK_MODELS = [
  {
    key: "overBudget",
    title: "Over-budget",
    question: "Will this sprint cost more effort than it was given?",
  },
  {
    key: "requirementChange",
    title: "Requirement change",
    question: "Are the requirements behind this sprint still moving?",
  },
  {
    key: "communicationCollaboration",
    title: "Collaboration",
    question: "Is the team still coordinating — replies, discussion, blockers, rework?",
  },
] as const;

export type RiskModelKey = (typeof RISK_MODELS)[number]["key"];

/** The weight keys as the aggregate names them, in the words the panels already use. */
export const WEIGHT_LABEL: Record<string, string> = {
  overBudget: "Over-budget",
  requirementChange: "Requirement change",
  communicationCollaboration: "Collaboration",
};
