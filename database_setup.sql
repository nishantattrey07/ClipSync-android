create extension if not exists pgcrypto with schema extensions;

drop function if exists public.get_clipboard_items(text, integer, uuid);
drop function if exists public.get_clipboard_items_paginated(text, integer, timestamptz, text);
drop function if exists public.insert_clipboard_item(uuid, text, text, text, text, text, text, text, text, text);
drop function if exists public.register_clipboard_device(text, uuid, text, smallint, text);
drop function if exists public.get_clipboard_devices(text);
drop function if exists public.insert_clipboard_item(uuid, text, uuid, text, text, smallint, text, text, text, text);
drop function if exists public.get_clipboard_items_paginated(text, integer, timestamptz, uuid, uuid);
drop function if exists public.get_clipboard_items_after(text, integer, timestamptz, uuid);

drop table if exists public.clipboard_device_credentials cascade;
drop table if exists public.clipboard_items cascade;
drop table if exists public.clipboard_devices cascade;

create table public.clipboard_devices (
    channel_id text not null check (channel_id ~ '^[0-9a-f]{64}$'),
    device_id uuid not null,
    profile_ciphertext text not null
        check (octet_length(profile_ciphertext) <= 65536),
    profile_version smallint not null default 1 check (profile_version > 0),
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now()),
    primary key (channel_id, device_id)
);

create table public.clipboard_device_credentials (
    channel_id text not null,
    device_id uuid not null,
    secret_hash bytea not null,
    primary key (channel_id, device_id),
    foreign key (channel_id, device_id)
        references public.clipboard_devices(channel_id, device_id)
        on delete cascade
);

create table public.clipboard_items (
    id uuid primary key,
    channel_id text not null check (channel_id ~ '^[0-9a-f]{64}$'),
    device_id uuid not null,
    kind text not null check (kind in ('text', 'image')),
    payload_version smallint not null default 1 check (payload_version > 0),
    ciphertext text check (ciphertext is null or octet_length(ciphertext) <= 6700000),
    image_path text check (
        image_path is null
        or image_path ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]enc$'
    ),
    thumbnail_ciphertext text
        check (thumbnail_ciphertext is null or octet_length(thumbnail_ciphertext) <= 2000000),
    mime_type text check (mime_type is null or octet_length(mime_type) <= 255),
    created_at timestamptz not null default timezone('utc', now()),
    foreign key (channel_id, device_id)
        references public.clipboard_devices(channel_id, device_id),
    check (
        (kind = 'text' and ciphertext is not null and image_path is null)
        or
        (kind = 'image' and image_path is not null and ciphertext is null)
    )
);

create index clipboard_items_channel_cursor_idx
    on public.clipboard_items(channel_id, created_at desc, id desc);

create index clipboard_items_channel_device_cursor_idx
    on public.clipboard_items(channel_id, device_id, created_at desc, id desc);

alter table public.clipboard_devices enable row level security;
alter table public.clipboard_device_credentials enable row level security;
alter table public.clipboard_items enable row level security;

revoke all on public.clipboard_devices from public, anon, authenticated;
revoke all on public.clipboard_device_credentials from public, anon, authenticated;
revoke all on public.clipboard_items from public, anon, authenticated;

grant select on public.clipboard_devices to anon, authenticated;
grant select on public.clipboard_items to anon, authenticated;

create policy "Encrypted device profiles are readable"
    on public.clipboard_devices
    for select
    to anon, authenticated
    using (true);

create policy "Encrypted clipboard items are readable"
    on public.clipboard_items
    for select
    to anon, authenticated
    using (true);

create or replace function public.register_clipboard_device(
    p_channel_id text,
    p_device_id uuid,
    p_profile_ciphertext text,
    p_profile_version smallint,
    p_device_secret text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_secret_hash bytea;
    v_stored_hash bytea;
begin
    if p_channel_id is null or p_channel_id !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid channel identifier' using errcode = '22023';
    end if;
    if p_profile_ciphertext is null or p_profile_ciphertext = '' then
        raise exception 'Encrypted device profile is required' using errcode = '22023';
    end if;
    if p_device_secret is null or p_device_secret !~ '^[A-Za-z0-9_-]{43}$' then
        raise exception 'Invalid device credential' using errcode = '22023';
    end if;

    v_secret_hash := extensions.digest(convert_to(p_device_secret, 'UTF8'), 'sha256');

    insert into public.clipboard_devices (
        channel_id, device_id, profile_ciphertext, profile_version
    ) values (
        p_channel_id, p_device_id, p_profile_ciphertext, p_profile_version
    ) on conflict (channel_id, device_id) do nothing;

    insert into public.clipboard_device_credentials (
        channel_id, device_id, secret_hash
    ) values (
        p_channel_id, p_device_id, v_secret_hash
    ) on conflict (channel_id, device_id) do nothing;

    select credentials.secret_hash
      into v_stored_hash
      from public.clipboard_device_credentials as credentials
     where credentials.channel_id = p_channel_id
       and credentials.device_id = p_device_id;

    if v_stored_hash is null or v_stored_hash <> v_secret_hash then
        raise exception 'Device credential rejected' using errcode = '42501';
    end if;

    update public.clipboard_devices as devices
       set profile_ciphertext = p_profile_ciphertext,
           profile_version = p_profile_version,
           updated_at = timezone('utc', now())
     where devices.channel_id = p_channel_id
       and devices.device_id = p_device_id;
end;
$$;

create or replace function public.get_clipboard_devices(
    p_channel_id text
)
returns setof public.clipboard_devices
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if p_channel_id is null or p_channel_id !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid channel identifier' using errcode = '22023';
    end if;

    return query
        select devices.*
          from public.clipboard_devices as devices
         where devices.channel_id = p_channel_id
         order by devices.created_at asc, devices.device_id asc;
end;
$$;

create or replace function public.insert_clipboard_item(
    p_id uuid,
    p_channel_id text,
    p_device_id uuid,
    p_device_secret text,
    p_kind text,
    p_payload_version smallint,
    p_ciphertext text default null,
    p_image_path text default null,
    p_thumbnail_ciphertext text default null,
    p_mime_type text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_authorized boolean;
    v_existing public.clipboard_items%rowtype;
begin
    if p_channel_id is null or p_channel_id !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid channel identifier' using errcode = '22023';
    end if;
    if p_device_secret is null or p_device_secret !~ '^[A-Za-z0-9_-]{43}$' then
        raise exception 'Invalid device credential' using errcode = '22023';
    end if;
    if p_payload_version is null or p_payload_version <= 0 then
        raise exception 'Invalid payload version' using errcode = '22023';
    end if;
    if p_image_path is not null
       and p_image_path !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]enc$' then
        raise exception 'Invalid image path' using errcode = '22023';
    end if;

    select exists (
        select 1
          from public.clipboard_device_credentials as credentials
         where credentials.channel_id = p_channel_id
           and credentials.device_id = p_device_id
           and credentials.secret_hash = extensions.digest(
               convert_to(p_device_secret, 'UTF8'), 'sha256'
           )
    ) into v_authorized;

    if not v_authorized then
        raise exception 'Device credential rejected' using errcode = '42501';
    end if;

    insert into public.clipboard_items (
        id,
        channel_id,
        device_id,
        kind,
        payload_version,
        ciphertext,
        image_path,
        thumbnail_ciphertext,
        mime_type
    ) values (
        p_id,
        p_channel_id,
        p_device_id,
        p_kind,
        p_payload_version,
        p_ciphertext,
        p_image_path,
        p_thumbnail_ciphertext,
        p_mime_type
    ) on conflict (id) do nothing;

    if found then
        return jsonb_build_object('status', 'inserted');
    end if;

    select items.*
      into v_existing
      from public.clipboard_items as items
     where items.id = p_id;

    if v_existing.channel_id = p_channel_id
       and v_existing.device_id = p_device_id
       and v_existing.kind = p_kind
       and v_existing.payload_version = p_payload_version
       and v_existing.ciphertext is not distinct from p_ciphertext
       and v_existing.image_path is not distinct from p_image_path
       and v_existing.thumbnail_ciphertext is not distinct from p_thumbnail_ciphertext
       and v_existing.mime_type is not distinct from p_mime_type then
        return jsonb_build_object('status', 'already_present');
    end if;

    raise exception 'Clipboard item UUID collision with different immutable data'
        using errcode = '23505';
end;
$$;

create or replace function public.get_clipboard_items_paginated(
    p_channel_id text,
    p_limit integer default 50,
    p_before_timestamp timestamptz default null,
    p_before_id uuid default null,
    p_device_id uuid default null
)
returns setof public.clipboard_items
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if p_channel_id is null or p_channel_id !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid channel identifier' using errcode = '22023';
    end if;

    return query
        select items.*
          from public.clipboard_items as items
         where items.channel_id = p_channel_id
           and (p_device_id is null or items.device_id = p_device_id)
           and (
               p_before_timestamp is null
               or items.created_at < p_before_timestamp
               or (
                   items.created_at = p_before_timestamp
                   and p_before_id is not null
                   and items.id < p_before_id
               )
           )
         order by items.created_at desc, items.id desc
         limit greatest(1, least(coalesce(p_limit, 50), 100));
end;
$$;

create or replace function public.get_clipboard_items_after(
    p_channel_id text,
    p_limit integer default 50,
    p_after_timestamp timestamptz default null,
    p_after_id uuid default null
)
returns setof public.clipboard_items
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if p_channel_id is null or p_channel_id !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid channel identifier' using errcode = '22023';
    end if;
    if (p_after_timestamp is null) <> (p_after_id is null) then
        raise exception 'Catch-up cursor requires both timestamp and ID' using errcode = '22023';
    end if;

    return query
        select items.*
          from public.clipboard_items as items
         where items.channel_id = p_channel_id
           and (
               p_after_timestamp is null
               or items.created_at > p_after_timestamp
               or (
                   items.created_at = p_after_timestamp
                   and items.id > p_after_id
               )
           )
         order by items.created_at asc, items.id asc
         limit greatest(1, least(coalesce(p_limit, 50), 100));
end;
$$;

revoke execute on function public.register_clipboard_device(text, uuid, text, smallint, text)
    from public;
revoke execute on function public.get_clipboard_devices(text)
    from public;
revoke execute on function public.insert_clipboard_item(uuid, text, uuid, text, text, smallint, text, text, text, text)
    from public;
revoke execute on function public.get_clipboard_items_paginated(text, integer, timestamptz, uuid, uuid)
    from public;
revoke execute on function public.get_clipboard_items_after(text, integer, timestamptz, uuid)
    from public;

grant execute on function public.register_clipboard_device(text, uuid, text, smallint, text)
    to anon, authenticated;
grant execute on function public.get_clipboard_devices(text)
    to anon, authenticated;
grant execute on function public.insert_clipboard_item(uuid, text, uuid, text, text, smallint, text, text, text, text)
    to anon, authenticated;
grant execute on function public.get_clipboard_items_paginated(text, integer, timestamptz, uuid, uuid)
    to anon, authenticated;
grant execute on function public.get_clipboard_items_after(text, integer, timestamptz, uuid)
    to anon, authenticated;

alter publication supabase_realtime add table public.clipboard_devices;
alter publication supabase_realtime add table public.clipboard_items;

insert into storage.buckets (
    id, name, public, file_size_limit, allowed_mime_types
)
values (
    'clipboard-images',
    'clipboard-images',
    false,
    51000000,
    array['application/octet-stream']::text[]
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "Allow all access to clipboard-images" on storage.objects;
drop policy if exists "ClipSync encrypted image insert" on storage.objects;
drop policy if exists "ClipSync encrypted image select" on storage.objects;

create policy "ClipSync encrypted image insert"
    on storage.objects
    for insert
    to anon, authenticated
    with check (bucket_id = 'clipboard-images');

create policy "ClipSync encrypted image select"
    on storage.objects
    for select
    to anon, authenticated
    using (bucket_id = 'clipboard-images');
