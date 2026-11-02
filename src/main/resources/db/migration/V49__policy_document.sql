-- Policies are written as a formatted document: page layout, header and footer lines, and who the policy applies to.
alter table policy
    add column page        jsonb not null default '{"size":"A4","margin":"Normal","orient":"Portrait","logo":true,"wm":true}',
    add column header_html text  not null default '<p>Policy handbook</p>',
    add column footer_html text  not null default '<p>Internal · Confidential</p>',
    add column applies_to  text  not null default 'All employees';
