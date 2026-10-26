-- a fixed-term hire needs a contract end date when the person joins
alter table onboarding_hire add column contract_end_date date;
