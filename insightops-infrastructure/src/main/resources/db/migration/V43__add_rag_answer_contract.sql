-- Historical evaluations remain unchanged; null means the final-answer contract was not checked.
alter table rag_evaluation_case add column answer_contract jsonb;
