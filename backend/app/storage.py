from functools import lru_cache
from typing import BinaryIO
from urllib.parse import quote

import boto3
from botocore.config import Config

from .config import get_settings


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
