from functools import lru_cache
import os
from pathlib import Path
from typing import BinaryIO
from urllib.parse import quote

import boto3
from botocore.config import Config

from .config import get_settings


def uses_local_storage() -> bool:
    return get_settings().attachment_storage_backend == "local"


def _local_path(object_key: str) -> Path:
    root = Path(get_settings().local_storage_dir).expanduser().resolve()
    candidate = (root / object_key).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as error:
        raise ValueError("Invalid attachment object key") from error
    return candidate


@lru_cache(maxsize=4)
def _client(endpoint_url: str):
    settings = get_settings()
    return boto3.client(
        "s3",
        endpoint_url=endpoint_url,
        region_name=settings.s3_region,
        aws_access_key_id=settings.s3_access_key,
        aws_secret_access_key=settings.s3_secret_key,
        config=Config(s3={"addressing_style": "path"}),
    )


def upload_attachment_object(
    fileobj: BinaryIO,
    object_key: str,
    content_type: str,
    filename: str,
) -> None:
    settings = get_settings()
    safe_filename = filename.replace('"', "").replace("\r", "").replace("\n", "")
    if uses_local_storage():
        destination = _local_path(object_key)
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary_path: Path | None = None
        try:
            with destination.with_name(destination.name + ".uploading").open("wb") as output:
                temporary_path = Path(output.name)
                while chunk := fileobj.read(64 * 1024):
                    output.write(chunk)
            os.replace(temporary_path, destination)
        finally:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)
        return
    _client(settings.s3_endpoint_url).upload_fileobj(
        fileobj,
        settings.s3_bucket,
        object_key,
        ExtraArgs={
            "ContentType": content_type,
            "ContentDisposition": f'inline; filename="{safe_filename}"',
        },
    )


def delete_attachment_object(object_key: str) -> None:
    settings = get_settings()
    if uses_local_storage():
        _local_path(object_key).unlink(missing_ok=True)
        return
    _client(settings.s3_endpoint_url).delete_object(
        Bucket=settings.s3_bucket,
        Key=object_key,
    )


def create_attachment_download_url(
    object_key: str,
    filename: str,
    content_type: str,
) -> str:
    settings = get_settings()
    if uses_local_storage():
        # The authenticated API content route streams local files without
        # exposing filesystem paths or creating an unauthenticated download URL.
        return ""
    endpoint = settings.s3_public_endpoint_url or settings.s3_endpoint_url
    safe_filename = filename.replace('"', "").replace("\r", "").replace("\n", "")
    disposition = f'inline; filename="{safe_filename}"; filename*=UTF-8\'\'{quote(safe_filename)}'
    return _client(endpoint).generate_presigned_url(
        "get_object",
        Params={
            "Bucket": settings.s3_bucket,
            "Key": object_key,
            "ResponseContentType": content_type,
            "ResponseContentDisposition": disposition,
        },
        ExpiresIn=settings.s3_presign_ttl_seconds,
    )


def get_local_attachment_path(object_key: str) -> Path:
    """Return the safe local object path for the authenticated download route."""
    if not uses_local_storage():
        raise RuntimeError("Local attachment storage is not enabled")
    return _local_path(object_key)
