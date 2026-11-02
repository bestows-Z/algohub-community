create table weekly_report_setting (
    school_id bigint primary key references school(id) on delete cascade,
    enabled boolean not null default false,
    enabled_since timestamptz,
    version integer not null default 0
);
create table weekly_report_rule (
    school_id bigint not null references weekly_report_setting(school_id) on delete cascade,
    effective_week date not null check (extract(isodow from effective_week)=1),
    day_of_week integer not null check (day_of_week between 1 and 7),
    minute_of_day integer not null check (minute_of_day between 0 and 1439),
    lead_hours integer not null check (lead_hours between 0 and 167),
    email_enabled boolean not null,
    primary key(school_id,effective_week),
    check(lead_hours*60 <= (day_of_week-1)*1440+minute_of_day)
);
create table weekly_report (
    id bigserial primary key,
    school_id bigint not null references school(id),
    user_id bigint not null references app_user(id) on delete cascade,
    week_start date not null check (extract(isodow from week_start)=1),
    enrollment_year integer,
    summary text not null default '',
    problems text not null default '',
    plan text not null default '',
    status varchar(16) not null default 'DRAFT' check(status in ('DRAFT','SUBMITTED','REVIEWED','RETURNED')),
    version integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    submitted_at timestamptz,
    unique(school_id,user_id,week_start)
);
create table weekly_report_event (
    id bigserial primary key,
    report_id bigint not null references weekly_report(id) on delete cascade,
    version integer not null,
    status varchar(16) not null,
    action varchar(16) not null,
    summary text not null,
    problems text not null,
    plan text not null,
    comment text not null default '',
    actor_id bigint references app_user(id) on delete set null,
    happened_at timestamptz not null,
    submitted_at timestamptz,
    unique(report_id,version)
);
create index weekly_report_scope on weekly_report(school_id,week_start,enrollment_year,id);
create index weekly_report_event_cutoff on weekly_report_event(report_id,happened_at desc,id desc);

-- Capture metadata/eligibility changes from all existing membership/account paths.
-- No email addresses or credentials are copied into reporting history.
create table weekly_report_roster_history (
    id bigserial primary key,
    member_id bigint not null,
    school_id bigint not null references school(id) on delete cascade,
    user_id bigint not null references app_user(id) on delete cascade,
    enrollment_year integer,
    class_name text,
    student_no text,
    display_name text not null,
    username text not null,
    eligible boolean not null,
    joined_at timestamptz not null,
    happened_at timestamptz not null default clock_timestamp()
);
create index weekly_report_roster_cutoff on weekly_report_roster_history(school_id,member_id,happened_at desc,id desc);
create function weekly_report_capture_user(uid bigint) returns void language sql as $$
    insert into weekly_report_roster_history(member_id,school_id,user_id,enrollment_year,class_name,student_no,
        display_name,username,eligible,joined_at)
    select m.id,m.school_id,m.user_id,m.enrollment_year,m.class_name,m.student_no,u.display_name,u.username,
        m.status='APPROVED' and u.status='ACTIVE' and u.role='STUDENT'
        and not exists(select 1 from school_administrator a where a.user_id=u.id),
        case when m.status='APPROVED' then coalesce(m.reviewed_at,m.applied_at) else m.applied_at end
    from school_member m join app_user u on u.id=m.user_id where m.user_id=uid;
$$;
create function weekly_report_member_changed() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' or (tg_op='UPDATE' and (old.school_id<>new.school_id or old.user_id<>new.user_id)) then
        insert into weekly_report_roster_history(member_id,school_id,user_id,enrollment_year,class_name,student_no,
            display_name,username,eligible,joined_at)
        select member_id,school_id,user_id,enrollment_year,class_name,student_no,display_name,username,false,joined_at
        from weekly_report_roster_history where member_id=old.id
        and exists(select 1 from app_user where id=old.user_id) order by happened_at desc,id desc limit 1;
    end if;
    if tg_op<>'DELETE' then perform weekly_report_capture_user(new.user_id); end if;
    return null;
end;
$$;
create trigger weekly_report_member_history after insert or update or delete on school_member
    for each row execute function weekly_report_member_changed();
create function weekly_report_user_changed() returns trigger language plpgsql as $$
begin
    perform weekly_report_capture_user(new.id);
    return null;
end;
$$;
create trigger weekly_report_user_history after update of role,status,display_name,username on app_user
    for each row execute function weekly_report_user_changed();
create function weekly_report_admin_changed() returns trigger language plpgsql as $$
begin
    if tg_op<>'INSERT' then perform weekly_report_capture_user(old.user_id); end if;
    if tg_op<>'DELETE' then perform weekly_report_capture_user(new.user_id); end if;
    return null;
end;
$$;
create trigger weekly_report_admin_history after insert or update or delete on school_administrator
    for each row execute function weekly_report_admin_changed();
select weekly_report_capture_user(user_id) from (select distinct user_id from school_member) members;

create table weekly_report_cycle (
    id bigserial primary key,
    school_id bigint not null references weekly_report_setting(school_id),
    week_start date not null,
    cutoff_at timestamptz not null,
    remind_at timestamptz,
    email_enabled boolean not null,
    reminder_done boolean not null default false,
    status varchar(16) not null default 'PENDING' check(status in ('PENDING','COMPLETE','FAILED','CANCELLED')),
    error varchar(160),
    generated_at timestamptz,
    eligible_count integer,
    submitted_count integer,
    unique(school_id,week_start)
);
create table weekly_report_missing (
    id bigserial primary key,
    cycle_id bigint not null references weekly_report_cycle(id) on delete cascade,
    user_id bigint not null references app_user(id) on delete cascade,
    enrollment_year integer,
    class_name text,
    student_no text,
    display_name text not null,
    username text not null,
    cutoff_status varchar(16) not null,
    last_edited_at timestamptz,
    reminder_status varchar(24),
    reminder_note varchar(160),
    reminder_sent_at timestamptz,
    unique(cycle_id,user_id)
);
create index weekly_report_missing_scope on weekly_report_missing(cycle_id,enrollment_year,id);
create table weekly_report_reminder (
    id bigserial primary key,
    cycle_id bigint not null references weekly_report_cycle(id) on delete cascade,
    user_id bigint not null references app_user(id) on delete cascade,
    enrollment_year integer,
    notification_id bigint not null unique references study_notification(id) on delete cascade,
    unique(cycle_id,user_id)
);
create table weekly_report_mail_event (
    id bigserial primary key,
    notification_id bigint not null references study_notification(id) on delete cascade,
    status varchar(24) not null,
    note varchar(160),
    sent_at timestamptz,
    happened_at timestamptz not null default clock_timestamp()
);
create index weekly_report_mail_cutoff on weekly_report_mail_event(notification_id,happened_at desc,id desc);
create function weekly_report_mail_changed() returns trigger language plpgsql as $$
begin
    if exists(select 1 from weekly_report_reminder where notification_id=new.notification_id) then
        insert into weekly_report_mail_event(notification_id,status,note,sent_at)
        values(new.notification_id,new.status,new.last_error,new.sent_at);
    end if;
    return null;
end;
$$;
create trigger weekly_report_mail_history after insert or update on study_mail_outbox
    for each row execute function weekly_report_mail_changed();
