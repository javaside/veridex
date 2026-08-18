{{- define "veridex.fullname" -}}
{{- printf "%s-veridex" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "veridex.labels" -}}
app.kubernetes.io/name: veridex
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "veridex.selectorLabels" -}}
app.kubernetes.io/name: veridex
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "veridex.backendSelectorLabels" -}}
{{- include "veridex.selectorLabels" (dict "Release" .Release "component" "backend") -}}
{{- end -}}

{{- define "veridex.webSelectorLabels" -}}
{{- include "veridex.selectorLabels" (dict "Release" .Release "component" "web") -}}
{{- end -}}

{{- define "veridex.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- printf "%s-backend" (include "veridex.fullname" .) -}}
{{- else -}}
{{- required "serviceAccount.name required when create=false" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* 入参: dict "global" .Values.global "image" .Values.backend.image */}}
{{- define "veridex.image" -}}
{{- $repo := .image.repository -}}
{{- if .global.imageRegistry -}}
{{- $repo = printf "%s/%s" .global.imageRegistry $repo -}}
{{- end -}}
{{- if .image.digest -}}
{{- printf "%s@%s" $repo .image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (required "image.tag is required when digest is empty" .image.tag) -}}
{{- end -}}
{{- end -}}

{{- define "veridex.checksum/backendConfig" -}}
checksum/config: {{ include (print $.Template.BasePath "/backend-configmap.yaml") . | sha256sum }}
{{- end -}}
