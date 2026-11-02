create table school_cohort_leader (
    id bigserial primary key,
    school_id bigint not null references school(id),
    enrollment_year integer not null check (enrollment_year between 2000 and 2100),
    user_id bigint not null references app_user(id) on delete cascade,
    member_id bigint not null references school_member(id) on delete cascade,
    granted_by bigint references app_user(id) on delete set null,
    created_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_by bigint references app_user(id) on delete set null,
    revoke_reason varchar(64)
);
create unique index school_cohort_leader_active_unique
    on school_cohort_leader(school_id,enrollment_year,user_id) where revoked_at is null;
create index school_cohort_leader_user_active on school_cohort_leader(user_id) where revoked_at is null;

-- Changing a membership back later must never resurrect an old delegation.
create function revoke_changed_cohort_membership() returns trigger language plpgsql as $$
begin
    if new.school_id is distinct from old.school_id
       or new.user_id is distinct from old.user_id
       or new.enrollment_year is distinct from old.enrollment_year
       or (old.status = 'APPROVED' and new.status is distinct from old.status) then
        update school_cohort_leader set revoked_at=now(),revoke_reason='MEMBERSHIP_CHANGED'
        where member_id=old.id and revoked_at is null;
    end if;
    return new;
end;
$$;
create trigger cohort_membership_changed before update on school_member
    for each row execute function revoke_changed_cohort_membership();

create function revoke_promoted_cohort_leader() returns trigger language plpgsql as $$
begin
    perform id from app_user where id=new.user_id for update;
    update school_cohort_leader set revoked_at=now(),revoke_reason='PROMOTED_TO_SCHOOL_ADMIN'
    where user_id=new.user_id and revoked_at is null;
    return new;
end;
$$;
create trigger cohort_school_admin_promoted before insert or update of user_id on school_administrator
    for each row execute function revoke_promoted_cohort_leader();

create function revoke_promoted_cohort_account() returns trigger language plpgsql as $$
begin
    if (new.role is distinct from old.role and new.role <> 'STUDENT')
       or (new.status is distinct from old.status and new.status <> 'ACTIVE') then
        update school_cohort_leader set revoked_at=now(),
            revoke_reason=case when new.status <> 'ACTIVE' then 'ACCOUNT_DISABLED' else 'ROLE_CHANGED' end
        where user_id=new.id and revoked_at is null;
    end if;
    return new;
end;
$$;
create trigger cohort_account_role_changed before update of role,status on app_user
    for each row execute function revoke_promoted_cohort_account();
